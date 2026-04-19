# Performance Analysis - FEDESP Clubs Import (`processClubNamesForSeason`)

**Analysis Date**: 2026-04-06  
**Scope**: `FedespClubInitialImportService.processClubNamesForSeason(baseFolder, season)` in  
`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/club/service/FedespClubInitialImportService.java`  
**Goal**: Reduce DB IOPs and end-to-end execution time.

---

## End-to-End Flow

```
processClubNamesForSeason(baseFolder, season)
  │
  ├─> resetAndLoadTextFilesForSeason()                  [FS: scan base folder, filter season folder]
  │   └─> FedespMatchResultDetailsByLineIterator
  │       ├─> FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()  [FS LIST]
  │       └─> processSeasonFolder(seasonFolderInfo)
  │           └─> Files.list(seasonFolder)              [FS LIST all files]
  │               └─> match rfetm-{season}-... regex, addToQueue()
  │
  ├─> importClubNames()
  │   └─> fetchCsvRowInfos()                            [MEMORY: materialize ALL rows into ArrayList]
  │
  └─> saveClubNamesInfo(rows)
      └─> extractClubNamesFromTeamNames(rows)
          ├─> Filter: remove rows where teamName matches quote-letter-quote pattern
          │   (removes inline team labels like 'A', 'B')
          ├─> Map each row → ClubNameAndYearInfo(teamName, season)
          └─> ClubNameGrouppingService.groupByCommonRoot()
              └─> FOR EACH team name (outer):           [O(n)]
                  └─> FOR EACH existing cluster (inner) [O(m) where m = clusters so far]
                      └─> levenshteinSimilarity()       [custom in-house Levenshtein]
                  └─> mergeSimilarGroups()              [additional pass over groups]

      └─> FOR EACH distinct club name in result map:
          ├─> clubRepository.findByName()               [DB SELECT per club]
          └─> clubRepository.save()                     [DB UPDATE if exists, INSERT if new]
```

---

## Findings (ordered by impact)

### 1) O(n²) Fuzzy Club Name Grouping — critical (for large datasets)

**Where**  
`ClubNameGrouppingService.groupByCommonRoot()` — shared utility used identically by FEDESP and BCNESA.

**What happens**  
For each team name encountered in the CSV rows, the algorithm scans all existing cluster roots and computes `levenshteinSimilarity()` for each. As the number of unique team names grows, the inner loop grows proportionally — worst case O(n²).

After the main grouping pass, `mergeSimilarGroups()` performs an additional O(m²) pass over the formed groups.

Each `levenshteinSimilarity()` call:
- Normalizes both strings (accent removal, lowercase via `normalize()`).
- Computes full Levenshtein distance using an O(|s1| × |s2|) DP algorithm.

**FEDESP-specific note**  
FEDESP club names typically follow `"CLUB TT MADRID 'A'"` style, where `'A'`/`'B'`/`'C'` designate teams within the same club. The pre-filter using `Pattern.compile("(['\"]{1,2})(.)(['\"]{1,2})")` removes these letter-labeled variants before grouping — which is correct behavior, but the filter is applied row by row during `toList()` inside `extractClubNamesFromTeamNames()`. The pattern is recompiled once (good), but the `ClubNameGrouppingService.normalize()` is still called repeatedly per comparison.

**Impact**  
For 400 team name rows across a season (many repeating), after deduplication there may be ~60–100 unique names to cluster. This is manageable but quadratic cost compounds on larger datasets.

---

### 2) Full In-Memory Row Materialization — high

**Where**  
`LineByLineInitialImportService.fetchCsvRowInfos()` — called from `importClubNames()`.

**What happens**  
All CSV rows are materialized into an `ArrayList` before name extraction starts. Only the team name column (`rowInfo()[3]` via `extractTeamNameFromRowInfo()`) is actually needed for this workflow; the rest of each row's data is unused.

**Impact**  
Unnecessary heap allocation carrying full row metadata for columns that are never read.

---

### 3) N+1 Database Query Pattern — high

**Where**  
`FedespClubInitialImportService.saveClubNamesInfo()`:

```java
cleanClubNamesAndYears.keySet().forEach(cleanClubName -> {
    Club clubToCreate = Club.createNew(cleanClubName);
    cleanClubNamesAndYears.get(cleanClubName).forEach(clubToCreate::addYearRange);
    createClubIfDoesntExistYet(clubToCreate);   // ← findByName + save
    completionTracker.trackIncrement();
});
```

`createClubIfDoesntExistYet()`:

```java
Optional<Club> existingClub = clubRepository.findByName(clubToCreate.getName());  // ← SELECT per club
if (existingClub.isPresent()) {
    existingClub.get().setYearRanges(clubToCreate.getYearRanges());
    clubRepository.save(existingClub.get());                                        // ← UPDATE
} else {
    clubRepository.save(clubToCreate);                                              // ← INSERT
}
```

**What happens**  
Every unique club name triggers:
- 1 SELECT to check existence.
- 1 INSERT (new) or 1 UPDATE (existing).

**Example**: 70 unique club names → **~140 DB round-trips**.

---

### 4) Unconditional Save on Existing Clubs — medium

**Where**  
`createClubIfDoesntExistYet()` when `existingClub.isPresent()`.

**What happens**  
If the club already exists, the code overwrites the `yearRanges` set with the data from the current CSV scan and calls `save()`. Even if the year ranges already contain the current season, the UPDATE is issued unconditionally.

**Impact**  
Extra UPDATE statements for clubs that haven't actually changed; unnecessary WAL writes in PostgreSQL.

---

### 5) `extractTeamNameFromRowInfo()` Wraps a Simple Column Access — low-medium

**Where**  
`FedespCsvFileRowInfoExtractor.extractTeamNameFromRowInfo()` returns `rowInfo.rowInfo()[3]`.

**What happens**  
The clubs import only needs column index 3 (local team name). It calls through `rowInfoExtractor.extractTeamNameFromRowInfo()` which correctly isolates this, but the full `FedespMatchResultsDetailCsvFileRowInfo` is in memory for all rows throughout. Column 5 (visitor team name) is never extracted here — only local team names are collected. This means visitor club names contribute nothing to club deduplication in this workflow.

**Impact**  
Structural gap: visitor team names are silently discarded during club import. Not a performance issue per se, but a data coverage issue — clubs only represented as visitors in a given season may not get imported.

---

### 6) `RuntimeException` for Non-Matching Filenames — high

**Where**  
`FedespMatchResultDetailsByLineIterator.processSeasonFolder()` — same issue as in all FEDESP workflows.

**What happens**  
Any non-RFETM file in the season folder aborts the club import entirely.

---

### 7) `Files.list()` Streams Not Closed — medium

**Where**  
Same as other workflows:
- `FedespMatchResultDetailsByLineIterator.processSeasonFolder()`.
- `FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()`.

---

## Estimated Cost Model

Assume a moderate FEDESP season: ~2,000 CSV rows with ~400 unique team name strings before grouping, ~70 unique club names after grouping:

| Phase | Estimated Time | Dominant Cost |
|---|---|---|
| Filesystem scan + queue | 50–150 ms | FS I/O |
| CSV read + materialization | 100–300 ms | Memory allocation |
| Team name filter + stream | 20–50 ms | Regex per row, stream ops |
| Fuzzy club name grouping (O(n²)) | 200–800 ms | Levenshtein × ~8,000 comparisons |
| DB SELECT (existence check) | 200–600 ms | ~70–100 SELECT queries |
| DB INSERT/UPDATE (save) | 150–400 ms | ~70–100 individual saves |
| **TOTAL** | **~0.7–2.3 seconds** | Grouping + DB queries |

### IOP Breakdown

| Category | Count | Notes |
|---|---|---|
| FS list operations | 2–4 | Season folder listing |
| CSV file reads | ~20–50 | Match files |
| DB SELECT (club lookups) | 70–100 | One per unique grouped club |
| DB INSERT/UPDATE | 70–100 | One per unique grouped club |
| **Total DB IOPs** | **~140–200** | |

> Clubs import is the lightest of the three workflows, but it runs first and its correctness is prerequisite for the results import.

---

## Prioritized Optimization Directions

1. **Bulk upsert clubs**: replace per-club `findByName + save` with a single `INSERT ... ON CONFLICT DO UPDATE`.
2. **Pre-fetch existing clubs**: `findAll()` once → `Set<String>` → filter without individual SELECTs.
3. **Skip UPDATE if year ranges already up to date**: compare before calling `save()`.
4. **Cache normalized club name** inside grouping service — avoid recomputing `normalize()` per comparison.
5. **Collect both local and visitor team names** — visitor-only clubs are currently silently missed.
6. **Stream rows** from iterator rather than materializing all rows.
7. **Handle non-matching filenames gracefully** (warn + skip) instead of `RuntimeException`.
8. **Close `Files.list()` with try-with-resources**.

See `PHASED_OPTIMIZATION_PLAN.md` for sequenced steps.

