# Performance Analysis - FEDESP Practicioners Import (`processPracticionersForSeason`)

**Analysis Date**: 2026-04-06  
**Scope**: `FedespPracticionerInitialImportService.processPracticionersForSeason(baseFolder, season)` in  
`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/club/service/FedespPracticionerInitialImportService.java`  
**Goal**: Reduce DB IOPs and end-to-end execution time.

---

## End-to-End Flow

```
processPracticionersForSeason(baseFolder, season)
  │
  ├─> resetAndLoadTextFilesForSeason()                  [FS: scan base folder, filter season folder]
  │   └─> FedespMatchResultDetailsByLineIterator
  │       ├─> FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()  [FS LIST]
  │       └─> processSeasonFolder(seasonFolderInfo)
  │           └─> Files.list(seasonFolder)              [FS LIST all files]
  │               └─> match rfetm-{season}-... regex, addToQueue()
  │
  ├─> importPracticioners()
  │   └─> fetchCsvRowInfos()                            [MEMORY: materialize ALL rows into ArrayList]
  │
  └─> savePracticionersInfo(rows)
      └─> extractPracticionersNames(rows)
          ├─> FOR EACH row: extract localPlayer.playerName() + visitorPlayer.playerName()
          │   └─> rowInfoExtractor.extractMatchDetailsRowInfo()  [re-parses each row]
          ├─> flatMap + .distinct()
          └─> PracticionerNameSimilarityService.reduceToSimilarClustersOfNames()
              └─> FOR EACH name (outer):               [O(n)]
                  └─> FOR EACH existing cluster (inner) [O(m) where m = clusters so far]
                      └─> NameSimilarity.similarity()   [EXPENSIVE: normalize + tokenize + SoftLevenshtein]

      └─> FOR EACH name in result cluster list:
          ├─> practicionerRepository.findByFullName()   [DB SELECT per name]
          └─> practicionerRepository.save()             [DB INSERT if not found]
```

---

## Findings (ordered by impact)

### 1) O(n²) Fuzzy Matching Clustering — critical

**Where**  
`PracticionerNameSimilarityService.reduceToSimilarClustersOfNames()` — shared utility used identically by both FEDESP and BCNESA.

**What happens**  
For each practitioner name in the deduplicated list, the algorithm performs a similarity comparison against every existing cluster root. As the number of names grows, the inner loop grows proportionally — worst case is O(n²) comparisons.

Each `NameSimilarity.similarity()` call:
- Normalizes both strings (accent removal, lowercase).
- Tokenizes both by whitespace.
- Runs `SoftLevenshtein.similarity()` for every token pair combination.

**Example cost**  
- 400 distinct names → up to ~80,000 similarity computations before clustering completes.
- Each computation: 10–100 µs depending on name length.
- Estimated total: **1–8 seconds of CPU time**.

**Note on FEDESP vs BCNESA**  
FEDESP match files include RFETM-licensed players with sometimes long composite names (first + surnames in various orderings). This can increase per-comparison cost relative to BCNESA.

---

### 2) Full In-Memory Row Materialization — high

**Where**  
`LineByLineInitialImportService.fetchCsvRowInfos()` — called from `importPracticioners()`.

**What happens**  
All CSV rows across the season are loaded into an `ArrayList<FedespMatchResultsDetailCsvFileRowInfo>` before any name extraction or deduplication starts.

**Impact**  
- Every row object carries the full `FedespMatchResultsDetailCsvFileInfo` metadata plus the raw `String[]` column array.
- A season with 2,000 matches = 2,000 row objects in memory simultaneously.
- No row is released until the entire name extraction is complete and the list goes out of scope.

---

### 3) N+1 Database Query Problem — high

**Where**  
`FedespPracticionerInitialImportService.savePracticionersInfo()`:

```java
practicionersNamesList.forEach(practicionerName -> {
    Practicioner practicionerToCreate = Practicioner.createNew(practicionerName, ...);
    if (practicionerRepository.findByFullName(practicionerName).isEmpty()) {   // ← SELECT per name
        practicionerRepository.save(practicionerToCreate);                     // ← INSERT if new
    }
    completionTracker.trackIncrement();
});
```

**What happens**  
For each distinct name after clustering:
- 1 SELECT to check existence.
- 1 INSERT if the name is new.

No batching. No bulk upsert.

**Example**: 300 distinct names after clustering → ~300 SELECT + ~250 INSERT = **~550 DB round-trips**.

---

### 4) Row Parsing Re-executed During Name Extraction — medium

**Where**  
`FedespPracticionerInitialImportService.extractPracticionersNames()`:

```java
fedespMatchResultsDetailCsvFileRowInfos.stream()
    .map(rowInfo -> {
        FedespMatchResultsDetailRowInfo fedespMatchResultsDetailRowInfo =
            rowInfoExtractor.extractMatchDetailsRowInfo(rowInfo);   // ← re-parses the row
        String localPracticionerName = fedespMatchResultsDetailRowInfo.localPlayer().playerName();
        String visitorPracticionerName = fedespMatchResultsDetailRowInfo.visitorPlayer().playerName();
        return List.of(localPracticionerName, visitorPracticionerName);
    })
```

**What happens**  
`extractMatchDetailsRowInfo()` re-parses the date, integer fields, and column indexes for every row, even though only the player names (`rowInfo[13]` for local, `rowInfo[17]` for visitor) are needed here.

**Impact**  
Wasted CPU parsing integers and date strings for every row. Low individually, multiplies across all rows.

---

### 5) `RuntimeException` for Any Non-Matching Filename — high

**Where**  
`FedespMatchResultDetailsByLineIterator.processSeasonFolder()` — identical issue as in the results workflow (this service uses the same iterator).

**What happens**  
Any file that does not match the `rfetm-{season}-..._matches.csv` pattern throws `RuntimeException`, aborting the import.

**Impact**  
Even for the practicioners workflow, a stray file in the season folder causes a full abort.

---

### 6) `Files.list()` Streams Not Closed — medium

**Where**  
- `FedespMatchResultDetailsByLineIterator.processSeasonFolder()`.
- `FedespCsvRepositoryFinderService.findAllSeasonsFoldersFrom()`.

**What happens**  
Neither call uses `try-with-resources`. The underlying `DirectoryStream` is not guaranteed to close promptly.

**Impact**  
File-handle leaks on repeated invocations or large directories.

---

### 7) Dead Code: `extractPracticionersNamesAndYears()` — low

**Where**  
`FedespPracticionerInitialImportService.extractPracticionersNamesAndYears()`:

```java
private Map<String, List<String>> extractPracticionersNamesAndYears(...) {
    // ...
    .filter(practicionerNameAndYearInfo -> practicionerNameAndYearInfo.practicionerName()
            .toLowerCase().contains("campos"))  // ← hardcoded debug filter left in
    // ...
}
```

**What happens**  
This method is never called. It also contains a hardcoded `.contains("campos")` debug filter, which would severely restrict output if ever accidentally invoked.

**Impact**  
Dead code with a debug artifact. Safe to remove.

---

### 8) No Batch Insert Configuration — medium

**Where**  
`practicionerRepository.save(practicionerToCreate)` — standard Spring Data JPA single-row save.

**What happens**  
Each `save()` issues a separate `INSERT` statement. Spring Data JPA does not batch inserts by default unless explicitly configured (`hibernate.jdbc.batch_size`).

**Impact**  
~250 individual `INSERT` statements where a single batched statement could do the work in far fewer round-trips.

---

## Estimated Cost Model

Assume a moderate FEDESP season: ~2,000 CSV rows, ~700 raw names (2 per row), ~400 distinct, ~280 after fuzzy clustering:

| Phase | Estimated Time | Dominant Cost |
|---|---|---|
| Filesystem scan + queue | 50–150 ms | FS I/O, file listing |
| CSV read + materialization | 100–300 ms | Memory allocation |
| Name extraction + dedup stream | 50–100 ms | Stream ops |
| Fuzzy clustering (O(n²)) | 1,500–8,000 ms | NameSimilarity × 80k comparisons |
| DB SELECT lookups | 500–1,500 ms | 280–400 SELECT round-trips |
| DB INSERT saves | 300–1,000 ms | ~230 individual INSERTs |
| **TOTAL** | **~2.5–11 seconds** | Fuzzy matching + DB queries |

### IOP Breakdown

| Category | Count | Notes |
|---|---|---|
| FS list operations | 2–4 | Season folder + season dir listing |
| CSV file reads | ~20–50 | One per match file |
| DB SELECT (existence check) | 280–400 | One per distinct name |
| DB INSERT (new records) | 200–280 | On miss |
| **Total DB IOPs** | **~480–680** | |

---

## Prioritized Optimization Directions

1. **Bulk upsert**: replace per-name `findByFullName + save` with a single `INSERT ... ON CONFLICT DO NOTHING`.
2. **Pre-fetch existing names**: `findAll()` once → build `Set<String>` of known full names → filter without individual SELECTs.
3. **Cache normalized names** inside `reduceToSimilarClustersOfNames()` — avoid recomputing normalization on every comparison.
4. **Add pre-filter before similarity**: length difference threshold to skip clearly non-matching pairs.
5. **Extract only player name columns** without full row re-parsing.
6. **Stream rows** from iterator — avoid full materialization.
7. **Wrap the save loop in a real `@Transactional` boundary** (via a separate service bean or public method).
8. **Handle non-matching files gracefully** (warn + skip, not `RuntimeException`).
9. **Close `Files.list()` with try-with-resources**.
10. **Remove `extractPracticionersNamesAndYears()` dead code** and the `"campos"` debug filter.

See `PHASED_OPTIMIZATION_PLAN.md` for sequenced steps and rollout guidance.

