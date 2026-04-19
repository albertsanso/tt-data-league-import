# Performance Analysis - BCNESA Results Import (`processForSeason`)

## Scope
- Entry point analyzed: `BcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolder, season)` in `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/service/BcnesaPlayerAndResultsInitialImportService.java:72`
- Goal: reduce DB IOPs and end-to-end execution time.

## End-to-end flow
1. Queue files for selected season (`resetAndLoadTextFilesForSeason`) via iterator/file finder.
2. Materialize all CSV rows (`fetchCsvRowInfos`) into memory.
3. Load all clubs + practicioners into memory (`findAll` once each).
4. For each row, infer local/visitor club and practicioner, then create/get:
   - `ClubMember`
   - `SeasonPlayer`
   - `SeasonPlayerResult`
   - `PlayersSingleMatch`

---

## Findings (ordered by impact)

### 1) Very high DB round-trips per row (critical)
**Where**
- `BcnesaPlayerAndResultsInitialImportService.java:306-307`, `397`, `403`, `415`, `421`, `340-353`, `382`, `207`, `226`

**What happens**
- For each player (local/visitor), the service does repeated read-then-save patterns.
- Approximate DB calls per processed row (not `D`):
  - Per player:
    - `findByName` + `findByFullName` = 2 reads
    - `findByPracticionerIdAndClubId` + `save` = 2 calls
    - `findByPracticionerIdClubIdSeason` + `save` = 2 calls
    - `findFor` + `save` = 2 calls
  - Two players: ~16 calls
  - Match row: `findBySeasonPlayerResultLocalIdAndSeasonPlayerResultVisitorIdAndUniqueId` (+ `save` on miss): +1/+2
- Total: ~17-18 DB calls per row.

**Why it hurts**
- Dominant IOP source.
- Network + transaction overhead scales linearly with rows.

---

### 2) `@Transactional` is effectively not applied (critical)
**Where**
- `BcnesaPlayerAndResultsInitialImportService.java:115-121`

**Why**
- Method is `private` and invoked from same class (`this` call path). Spring proxy-based transaction interception does not apply in this pattern.

**Impact**
- No row-level transaction boundary despite annotation intent.
- Repository method calls likely run in many small transactions (extra overhead).

---

### 3) Expensive CPU inference loops per player (high)
**Where**
- Club inference: `BcnesaPlayerAndResultsInitialImportService.java:284-288`
- Practicioner inference: `BcnesaPlayerAndResultsInitialImportService.java:290-293`
- Similarity algo: `tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/name/NameSimilarity.java:7-47`

**What happens**
- For every row, for both players:
  - Club: full scan over all clubs with Levenshtein distance.
  - Practicioner: full scan over all practicioners with `NameSimilarity.similarity`.
- Complexity approximately:
  - `O(rows * (clubs + practicioners * token_similarity_cost))`

**Extra cost**
- `normalize(club.getName())` is recomputed for each comparison (`replaceAll` regex path).

---

### 4) Full in-memory row materialization (high)
**Where**
- `LineByLineInitialImportService.java:23-29`
- Called from `BcnesaPlayerAndResultsInitialImportService.java:82-84`

**What happens**
- All rows of the season are loaded before processing starts.

**Impact**
- Higher heap usage and GC pressure.
- No overlap of read/process/write stages.

---

### 5) Unnecessary writes on existing entities (medium-high)
**Where**
- `BcnesaPlayerAndResultsInitialImportService.java:403`, `421`, `382`

**What happens**
- `save` is called even when entity already exists and no effective state change is guaranteed.

**Impact**
- Extra UPDATE statements / flush work.
- More lock and WAL churn in PostgreSQL.

---

### 6) Exception path is expensive and masks data-quality failures (medium)
**Where**
- Potential null path: `BcnesaPlayerAndResultsInitialImportService.java:137-138` after nullable creation at `265`
- Catch-all with stack trace per row: `104-109`

**What happens**
- If local/visitor inference fails, null values can propagate and trigger exceptions.
- Printing stack traces per row is expensive I/O and can dominate runtime when data quality is bad.

---

### 7) Filesystem listing streams are not explicitly closed (medium)
**Where**
- `BcnesaMatchResultDetailsByLineIterator.java:89`
- `BcnesaCsvRepositoryFinderService.java:49`, `59`

**What happens**
- `Files.list(...)` returns a stream backed by OS resources; current code does not use try-with-resources.

**Impact**
- Potential handle leakage and slower scans on larger trees.

---

### 8) Smaller hotspots (low)
- Date formatter allocated per row: `BcnesaCsvFileRowInfoExtractor.java:30`
- UUID generated per row in iterator: `MatchResultDetailsByLineIterator.java:41`; appears unused by bcnesa result flow.
- Progress bar configured at 1% step (`BcnesaPlayerAndResultsInitialImportService.java:91`): acceptable, but still console I/O.

---

## Estimated cost model
Assume 30k rows season, 95% non-`D` rows (~28.5k processed):
- DB calls: ~28.5k * 17 ~= **484k DB calls** (plus writes on misses)
- CPU inference comparisons:
  - club comparisons ~= rows * 2 * clubCount
  - practicioner comparisons ~= rows * 2 * practicionerCount * token matching cost

This explains long wall-clock time even with a warm DB.

---

## Prioritized optimization ideas (high-level)
1. Batch/cached identity resolution to remove per-row `findByName` and `findByFullName`.
2. Make transaction boundaries explicit and coarse enough (batch transaction), not per repository call.
3. Precompute normalized club/practicioner keys and add inference caches.
4. Process iterator rows incrementally (streaming) instead of loading all rows in memory.
5. Save only when state changed; use batch flush windows.
6. Replace stack-trace-per-row with bounded error counters and sampled logs.
7. Close `Files.list` streams with try-with-resources.

For concrete steps and rollout sequence, see `performance-plans/results/PHASED_OPTIMIZATION_PLAN.md`.

