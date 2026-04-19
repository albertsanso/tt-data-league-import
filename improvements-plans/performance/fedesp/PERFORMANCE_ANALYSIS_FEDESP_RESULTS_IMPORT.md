# Performance Analysis - FEDESP Results Import (`processForSeason` / `processForAllSeasons`)

**Analysis Date**: 2026-04-06  
**Scope**: `FedespPlayerAndResultsImportService.processForSeason(baseFolder, season)` in  
`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/player_single_match/service/FedespPlayerAndResultsImportService.java`  
**Goal**: Reduce DB IOPs and end-to-end execution time.

---

## End-to-End Flow

```
processForSeason(baseFolder, season)
  │
  ├─> resetAndLoadTextFilesForSeason()                  [FS: scan base folder, filter season folder]
  │   └─> FedespMatchResultDetailsByLineIterator
  │       ├─> FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()  [FS LIST]
  │       └─> processSeasonFolder(seasonFolderInfo)
  │           └─> Files.list(seasonFolder)  [FS LIST all files]
  │               └─> match rfetm-{season}-... regex, addToQueue()
  │
  ├─> importMatchResultsDetailsInfo()
  │   └─> fetchCsvRowInfos()                            [MEMORY: materialize ALL rows]
  │
  └─> processMatchResultsDetailsInfo(rows)
      ├─> clubRepository.findAll()                      [DB READ: all clubs]
      ├─> practicionerRepository.findAll()              [DB READ: all practicioners]
      │
      └─> FOR EACH row (non-D):
          ├─> rowInfoExtractor.extractMatchDetailsRowInfo()
          ├─> inferClubByTeamName(local)                [CPU: scan all clubs via Levenshtein]
          ├─> inferClubByTeamName(visitor)              [CPU: scan all clubs via Levenshtein]
          ├─> inferPracticionerByName(local)            [CPU: scan all practicioners via NameSimilarity]
          ├─> inferPracticionerByName(visitor)          [CPU: scan all practicioners via NameSimilarity]
          │
          ├─> createSeasonPlayerAndResultsAsLocal(...)
          │   ├─> clubRepository.findByName()           [DB READ]
          │   ├─> practicionerRepository.findByFullName() [DB READ]
          │   ├─> clubMemberRepository.findByPracticionerIdAndClubId() [DB READ]
          │   ├─> clubMemberRepository.save()           [DB WRITE]
          │   ├─> seasonPlayerRepository.findByPracticionerIdClubIdSeason() [DB READ]
          │   ├─> seasonPlayerRepository.save()         [DB WRITE]
          │   ├─> seasonPlayerResultRepository.findFor() [DB READ]
          │   └─> seasonPlayerResultRepository.save()   [DB WRITE]
          │
          ├─> createSeasonPlayerAndResultsAsVisitor(...)  [same 6 calls]
          │
          └─> createPlayersSingleMatchIfNotExists(...)
              ├─> playersSingleMatchRepository.findBySeasonPlayerResultLocalId...() [DB READ]
              └─> playersSingleMatchRepository.save() (on miss) [DB WRITE]
```

---

## Findings (ordered by impact)

### 1) Very high DB round-trips per row — critical

**Where**  
`FedespPlayerAndResultsImportService.java` — `createSeasonPlayerAndResultsForClub()` at lines ~280–330, ~340–360, plus `createPlayersSingleMatchIfNotExists()`.

**What happens**  
For each non-`D` player row, both the local and visitor player go through the same call chain:

| Call | Type | Count per player |
|---|---|---|
| `clubRepository.findByName()` | SELECT | 1 |
| `practicionerRepository.findByFullName()` | SELECT | 1 |
| `clubMemberRepository.findByPracticionerIdAndClubId()` | SELECT | 1 |
| `clubMemberRepository.save()` | INSERT / UPDATE | 1 |
| `seasonPlayerRepository.findByPracticionerIdClubIdSeason()` | SELECT | 1 |
| `seasonPlayerRepository.save()` | INSERT / UPDATE | 1 |
| `seasonPlayerResultRepository.findFor()` | SELECT | 1 |
| `seasonPlayerResultRepository.save()` | INSERT / UPDATE | 1 |

Two players per row → **16 DB calls/row** + 1–2 more for `PlayersSingleMatch` lookup+save = **~17–18 DB calls/row**.

**Why it hurts**  
Each call is a round-trip over the JDBC connection. This scales linearly with rows — 2,000 rows = ~36,000 DB calls.

---

### 2) `findByName` / `findByFullName` are redundant re-lookups — critical

**Where**  
`FedespPlayerAndResultsImportService.java` — `createSeasonPlayerAndResultsForClub()` calls `clubRepository.findByName(inferredClub.getName())` and `practicionerRepository.findByFullName(inferredPracticioner.getFullName())`.

**What happens**  
The clubs and practicioners are already loaded via `findAll()` at the start of the loop. Inference selects an entity from that in-memory list. Then the code performs a second DB lookup to get "the same entity from DB" before proceeding. This lookup is entirely redundant — the entity is already in memory.

**Impact**  
Adds 2 unnecessary SELECT calls per player, per row = **4 extra DB calls/row**.

---

### 3) `@Transactional` is effectively not applied — critical

**Where**  
`processMatchResultsDetailsInfo()` is called from `importMatchResultsDetailsInfo()` on the same bean. Spring proxy-based `@Transactional` only intercepts calls crossing the proxy (external calls). Self-invocations bypass it entirely.

**Impact**  
- No transaction wraps the per-row processing chain.
- Each repository call executes in its own micro-transaction.
- Extra commit overhead per call.

---

### 4) Inference has no minimum similarity threshold for practicioners — high

**Where**  
`FedespPlayerAndResultsImportService.inferPracticionerByName()`:

```java
return allPracticionersList.stream()
    .max(Comparator.comparingDouble(practicioner -> NameSimilarity.similarity(practicionerName, practicioner.getFullName())));
```

**What happens**  
`max()` returns the highest-scoring practicioner regardless of the absolute score. If the best score is 0.05, the function still returns that practicioner as a "match".  
The BCNESA equivalent also suffers this issue; FEDESP inherited the same pattern.

**Impact**  
- Silent wrong practicioner assignments.
- Downstream data corruption that is hard to detect.

---

### 5) Expensive CPU inference loops per row — high

**Where**  
- Club inference: `inferClubByTeamName()` — full scan over all clubs using `levenshtein.apply()`.
- Practicioner inference: `inferPracticionerByName()` — full scan over all practicioners using `NameSimilarity.similarity()`.
- Both called twice per row (local + visitor).

**What happens**  
- `O(rows × 2 × clubCount)` Levenshtein distance computations.
- `O(rows × 2 × practicionerCount × token_similarity_cost)` similarity computations.
- `normalize()` (local private method) recomputes `toLowerCase().replaceAll(...)` for **every club on every row**.

**Additional FEDESP issue**  
The local `normalize()` method removes all non-alphanumeric characters and strips `"fc"`. This is inconsistent with the shared `NameNormalizer` used elsewhere — it produces different normalized forms and can cause edge-case mismatches.

---

### 6) Full in-memory row materialization before processing — high

**Where**  
`LineByLineInitialImportService.fetchCsvRowInfos()` — called from `importMatchResultsDetailsInfo()`.

**What happens**  
All rows across all matched CSV files are loaded into an `ArrayList` before the processing loop starts. No streaming or incremental processing.

**Impact**  
- O(n) heap allocation.
- Potential GC pressure on large seasons.
- No overlap between read and write stages.

---

### 7) `RuntimeException` thrown for any non-matching filename — high

**Where**  
`FedespMatchResultDetailsByLineIterator.processSeasonFolder()`:

```java
Files.list(Path.of(seasonFolderInfo.folder())).forEach(csvFilePath -> {
    Matcher matcher = csvFilePattern.matcher(csvFilePath.getFileName().toString());
    if (matcher.matches()) {
        // ...
    } else {
        throw new RuntimeException("Wrong file name format for match results details: %s".formatted(csvFilePath));
    }
});
```

**What happens**  
Any file that doesn't match the FEDESP naming convention (e.g., `.DS_Store`, `README.txt`, temp files) causes the entire import to abort mid-season.

**Impact**  
- Import fails completely if the filesystem contains any unexpected file.
- Not caught — propagates all the way up.

---

### 8) Silent date fallback to `ZonedDateTime.now()` on parse error — high

**Where**  
`FedespCsvFileRowInfoExtractor.parseZonedDateTime()`:

```java
try {
    zonedDateTime = LocalDateTime.parse(...).atZone(ZoneId.systemDefault());
} catch (DateTimeParseException e) {
    e.printStackTrace();  // stack trace to stdout, then continues with now()
}
return zonedDateTime;
```

**What happens**  
If the date in the CSV can't be parsed, the code prints a stack trace but silently returns the current timestamp instead of the actual match date. This persists incorrect data into the database with no warning at row level.

**Impact**  
- Corrupts match date data silently.
- Stack trace per bad row is expensive I/O.

---

### 9) `DateTimeFormatter` re-created on every call — medium

**Where**  
`FedespCsvFileRowInfoExtractor.parseZonedDateTime()` — `DateTimeFormatter.ofPattern(...)` is called inside the method, once per row.

**What happens**  
A new `DateTimeFormatter` instance is allocated for every row processed. The formatter is stateless and reusable.

**Impact**  
GC allocation pressure (minor individually, adds up over thousands of rows).

---

### 10) `Files.list()` streams not closed — medium

**Where**  
- `FedespMatchResultDetailsByLineIterator.processSeasonFolder()` — `Files.list(Path.of(...)).forEach(...)`.
- `FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()` — `Files.list(baseFolderPath)...toList()` (no explicit close).

**What happens**  
`Files.list()` returns a `Stream` backed by a `DirectoryStream` OS resource. Calling `.forEach()` or `.toList()` without `try-with-resources` means the underlying handle is only closed when the stream is GC'd (implementation-dependent).

**Impact**  
File-handle leaks on large trees or repeated invocations.

---

### 11) Hardcoded competition metadata — medium

**Where**  
`FedespMatchResultDetailsByLineIterator.processSeasonFolder()`:

```java
FedespMatchResultsDetailCsvFileInfo info = new FedespMatchResultsDetailCsvFileInfo(
    csvFilePath,
    seasonFolderInfo.season(),
    "senior",       // competitionType — hardcoded
    categoria,
    "nacional",     // competitionScope — hardcoded
    "esp",          // competitionScopeTag — hardcoded
    gender,
    groupNumber,
    "");
```

**What happens**  
`competitionType`, `competitionScope`, and `competitionScopeTag` are hardcoded strings, not derived from file metadata.

**Impact**  
- No support for non-senior or regional FEDESP competitions without code change.
- Incorrect data for any file that represents a different competition tier.

---

### 12) Unnecessary saves on existing entities — medium

**Where**  
`getOrCreateClubMember()`, `getOrCreateSeasonPlayer()`, `getOrCreateSeasonPlayerResult()`.

**What happens**  
`save()` is called unconditionally after `find...()`, regardless of whether the entity was newly created or was already present with unchanged fields.

**Impact**  
- Extra UPDATE statements / Hibernate flush cycles even when there is nothing to change.
- WAL and lock churn in PostgreSQL.

---

### 13) Dead code — low

**Where**  
`FedespPlayerAndResultsImportService.splitIntoFirstNameAndSecondName()` — private method, never called.

**Impact**  
Noise. Minor risk of future confusion.

---

## Estimated Cost Model

Assume a moderate FEDESP season: ~2,000 CSV rows, ~95% non-`D` (~1,900 processed):

| Category | Estimate |
|---|---|
| DB calls (results) | ~1,900 × 17 ≈ **32,300 DB calls** |
| Inference: club comparisons | ~1,900 × 2 × clubCount |
| Inference: practicioner comparisons | ~1,900 × 2 × practicionerCount × token cost |
| Redundant `findByName` / `findByFullName` | ~1,900 × 4 = ~7,600 extra SELECT calls |

This explains why large seasons take many minutes even with a local PostgreSQL instance.

---

## Prioritized Optimization Directions

1. **Remove redundant `findByName`/`findByFullName`** — the entity is already in the in-memory list after inference.
2. **Cache `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult`** — hit memory first, DB only on cache miss.
3. **Save only on create or mutation** — skip unconditional `save()`.
4. **Fix transaction boundary** — use a separate Spring bean for `public @Transactional` row processing.
5. **Add minimum similarity threshold to `inferPracticionerByName`** — prevent wrong matches.
6. **Memoize inference results** — many rows reference the same teams and players.
7. **Precompute normalized keys** for club inference.
8. **Stream rows incrementally** — remove full materialization.
9. **Handle non-matching filenames gracefully** (warn, skip) instead of `RuntimeException`.
10. **Fix date-parse fallback** — log a bounded warning, do not silently store `now()`.
11. **Promote `DateTimeFormatter` to a static constant**.
12. **Close `Files.list()` with try-with-resources**.

See `PHASED_OPTIMIZATION_PLAN.md` for sequenced steps.

