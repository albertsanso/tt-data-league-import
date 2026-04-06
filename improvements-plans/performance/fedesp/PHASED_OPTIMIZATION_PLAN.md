# Phased Optimization Plan - FEDESP Workflows

**Target**: Reduce DB IOPs, CPU cost, and execution time across all three FEDESP import workflows:  
`FedespClubInitialImportService`, `FedespPracticionerInitialImportService`, `FedespPlayerAndResultsImportService`.

## Principles
- Keep behavior idempotent.
- Prefer low-risk, high-impact changes first.
- Measure each phase before moving to the next.
- Do not introduce FEDESP-specific logic into `shared`; do not move shared logic into `runtime`.
- Treat shared module changes (`LineByLineInitialImportService`, `PracticionerNameSimilarityService`, `ClubNameGrouppingService`) as coordinated cross-adapter changes — validate BCNESA is unaffected.

---

## Phase 0 — Instrument First (0.5–1 day)

### Objective
Build a baseline before optimizing. Without measurement, improvements are unverifiable.

### Actions
1. Add per-stage wall-clock timers to all three FEDESP services:
   - File discovery (`resetAndLoadTextFilesForSeason`)
   - CSV read + materialization (`fetchCsvRowInfos`)
   - Fuzzy clustering (clubs grouping, practicioner clustering)
   - DB persistence loop
2. Add row-level counters:
   - Total rows read
   - Rows skipped (where applicable)
   - Inference misses (club not found, practicioner not found)
   - DB hit/miss per entity type
3. Log totals at the end of each workflow run.
4. (Optional) Enable Hibernate SQL logging for a sample run to count statements.

### Success criteria
- Can report exact total DB statements and ms per stage for one FEDESP season run.
- Counters confirm inference miss rate.

---

## Phase 1 — Cut DB Chattiness (highest ROI, 2–4 days)

Targets all three workflows. Reduces per-row DB calls from ~17–18 to ~3–5 for results.

### 1.1 Remove redundant `findByName` / `findByFullName` in results import

**Current pain**  
`createSeasonPlayerAndResultsForClub()` calls `clubRepository.findByName(inferredClub.getName())` and `practicionerRepository.findByFullName(inferredPracticioner.getFullName())` even though inference already returned the entity from the in-memory list loaded by `findAll()`.

**Change**  
Pass the already-inferred `Club` and `Practicioner` objects directly into `createSeasonPlayerAndResultsForClub()` rather than re-querying them from the DB.

**Expected impact**  
Removes 4 SELECT calls per row = ~7,600 fewer DB calls for a 1,900-row season.

---

### 1.2 Introduce in-memory get-or-create caches for results import

**Current pain**  
`ClubMember`, `SeasonPlayer`, `SeasonPlayerResult` perform `find... + save` on every row, even for entities that were just created on a previous row.

**Change**  
Add in-process maps keyed by natural keys, populated at season start by loading existing records or on first creation:

```
Map<String, ClubMember>          clubMemberCache    key: "practicionerId::clubId"
Map<String, SeasonPlayer>        seasonPlayerCache  key: "practicionerId::clubId::season"
Map<String, SeasonPlayerResult>  spr­Cache          key: "season::compType::compCat::compScope::compScopeTag::compGroup::matchDay::playerLetter::pairingKey::teamRole::clubId"
Map<String, PlayersSingleMatch>  matchCache         key: uniqueRowId
```

On cache miss → query DB and populate cache. On cache hit → use in-memory entity.

**Expected impact**  
For a season where many rows reference the same players: 70–90% reduction in `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult` SELECT calls.

---

### 1.3 Save only when state changed

**Current pain**  
`clubMemberRepository.save()`, `seasonPlayerRepository.save()`, and `seasonPlayerResultRepository.save()` are called unconditionally after `find...`, even when the entity already existed and nothing changed.

**Change**  
Only call `save()` when the entity was newly created (not retrieved from cache/DB).

**Expected impact**  
Reduces UPDATE statements / WAL churn; lower lock contention.

---

### 1.4 Pre-fetch existing practicioners and clubs; replace N+1 in clubs and practicioners imports

**Current pain**  
Both `FedespPracticionerInitialImportService` and `FedespClubInitialImportService` perform 1 SELECT per name/club before saving.

**Change — practicioners**:
```java
// Before persistence loop:
Set<String> existingNames = new HashSet<>(practicionerRepository.findAllFullNames());

// In loop:
if (!existingNames.contains(practicionerName)) {
    practicionerRepository.save(Practicioner.createNew(practicionerName, ...));
    existingNames.add(practicionerName);  // keep cache consistent
}
```

**Change — clubs**:
```java
// Before persistence loop:
Map<String, Club> existingClubs = clubRepository.findAll().stream()
    .collect(Collectors.toMap(Club::getName, Function.identity()));

// In loop:
Club existing = existingClubs.get(cleanClubName);
if (existing != null) {
    if (!existing.getYearRanges().containsAll(yearRanges)) {
        existing.setYearRanges(...);
        clubRepository.save(existing);
    }
    // else: skip save entirely
} else {
    Club newClub = Club.createNew(cleanClubName);
    yearRanges.forEach(newClub::addYearRange);
    clubRepository.save(newClub);
}
```

**Expected impact**  
Reduces ~300–400 individual SELECTs for practicioners and ~70–100 for clubs to a single `findAll()` each.

---

### 1.5 Add minimum similarity threshold to practicioner inference

**Current pain**  
`inferPracticionerByName()` always returns the highest-scoring practicioner regardless of score. A score of 0.05 is treated as a valid match.

**Change**  
Apply a minimum absolute threshold (e.g., 0.50) before accepting an inference result:

```java
private static Optional<Practicioner> inferPracticionerByName(String name, List<Practicioner> all) {
    return all.stream()
        .map(p -> Map.entry(p, NameSimilarity.similarity(name, p.getFullName())))
        .filter(e -> e.getValue() >= 0.50)
        .max(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey);
}
```

**Expected impact**  
Prevents silent wrong-practicioner assignments. Surfaced misses become visible (logs UNABLE TO INFER) rather than persisting corrupt data.

---

## Phase 2 — Fix Transaction Model + Batch Writes (1–2 days)

### 2.1 Make transaction boundary real in results import

**Current pain**  
The row-processing method calls are private / self-invoked. Spring AOP proxy does not intercept them.

**Change options** (pick one):
1. Extract row processing into a separate Spring bean with a `public @Transactional` method called per batch of rows.
2. Annotate a newly exposed `public processRowBatch(List<...> rows, ...)` method on a helper bean.

**Expected impact**  
Fewer network round-trips per row; rollback safety per batch.

### 2.2 Batch persistence for practicioners and clubs

**Change**  
Configure Hibernate JDBC batching in `application.properties` (already applies via runtime module):

```properties
spring.jpa.properties.hibernate.jdbc.batch_size=50
spring.jpa.properties.hibernate.order_inserts=true
spring.jpa.properties.hibernate.order_updates=true
```

Flush/clear the persistence context every N entities (e.g., every 100) when saving bulk data.

**Expected impact**  
Fewer network round-trips for INSERT-heavy phases (practicioners import).

---

## Phase 3 — Reduce CPU Inference Cost (2–3 days)

### 3.1 Precompute and cache normalized values

In `FedespPlayerAndResultsImportService`:

- Replace the local private `normalize()` with the shared `NameNormalizer.normalize()` for consistency.
- Build `Map<String, String> normalizedClubNames` once at loop start:
  ```java
  Map<Club, String> normalizedClubNames = allClubsList.stream()
      .collect(Collectors.toMap(Function.identity(), c -> normalize(c.getName())));
  ```
- Reuse precomputed values inside `inferClubByTeamName()`.

### 3.2 Memoize inference results

Most rows in a season reference a small set of recurring team names and player names.

**Change**:
```java
Map<String, Optional<Club>>        clubInferenceCache        = new HashMap<>();
Map<String, Optional<Practicioner>> practicionerInferenceCache = new HashMap<>();

// In inference:
clubInferenceCache.computeIfAbsent(teamName, name -> inferClubByTeamName(name, allClubsList));
practicionerInferenceCache.computeIfAbsent(playerName, name -> inferPracticionerByName(name, allPracticionersList));
```

**Expected impact**  
For a typical season where ~30–50 clubs and ~300 practicioners appear across 2,000 rows:
- Club inference: from ~3,800 scans down to ~30–50 unique scans.
- Practicioner inference: from ~3,800 scans down to ~300–400 unique scans.

### 3.3 Add cheap pre-filters before expensive similarity

In `ClubNameGrouppingService` and `PracticionerNameSimilarityService`:

- If `Math.abs(a.length() - b.length()) > threshold`, skip similarity check.
- If no common leading character after normalization, skip.

**Expected impact**  
Significant reduction in inner-loop similarity calls, especially for clearly non-matching names.

### 3.4 Normalize `normalize()` usage

Replace the local `normalize()` in `FedespPlayerAndResultsImportService` (which produces different normalization from the shared utility) with a consistent call to the shared `NameNormalizer`. Document this change to avoid future divergence.

---

## Phase 4 — Stream Rows Instead of Materializing All (2–3 days)

**Current pain**  
`fetchCsvRowInfos()` loads all rows into memory before any processing.

**Change**  
Modify or extend `LineByLineInitialImportService` to expose an incremental iteration path:

```java
// Option A: expose the iterator directly
protected MatchResultDetailsByLineIterator<...> getIterator() { return matchResultDetailsByLineIterator; }

// Option B: add a streaming overload in the subclass
while (matchResultDetailsByLineIterator.hasNext()) {
    FileRowInfo row = matchResultDetailsByLineIterator.next();
    processRow(row, caches...);
}
```

Keep the existing `fetchCsvRowInfos()` path for use cases that genuinely need the full list (e.g., fuzzy clustering for practicioners/clubs still requires full materialization for the grouping algorithm).

**Note on clubs and practicioners workflows**  
The fuzzy clustering algorithms (`groupByCommonRoot`, `reduceToSimilarClustersOfNames`) require the full name list before they can cluster. For these workflows, streaming rows is only useful up to the point of name collection; the clustering step still needs all names. This optimization is most impactful for the results import.

**Expected impact**  
- Results import: significantly lower peak heap usage.
- Better time-to-first-write.
- Reduced GC pause frequency.

---

## Phase 5 — I/O Robustness, Log Hygiene, Dead-Code Removal (0.5–1 day)

### 5.1 Handle non-matching filenames gracefully

**Change**  
In `FedespMatchResultDetailsByLineIterator.processSeasonFolder()`, replace `throw new RuntimeException` with a bounded warning:

```java
} else {
    System.err.println("[FEDESP] Skipping unrecognized file: " + csvFilePath.getFileName());
}
```

Optionally, count skipped files and log a summary at the end of the season scan.

### 5.2 Fix date-parse fallback

**Change**  
In `FedespCsvFileRowInfoExtractor.parseZonedDateTime()`:
- Remove the catch that falls back to `ZonedDateTime.now()`.
- Instead, return `Optional<ZonedDateTime>` and let the caller decide (log a warning at row level, increment a bad-date counter, use `null` or a sentinel value).

### 5.3 Promote `DateTimeFormatter` to a static constant

```java
private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm");
```

### 5.4 Close `Files.list()` with try-with-resources

In `processSeasonFolder()` and `findAllSeasonsFoldersFrom()`:

```java
try (Stream<Path> files = Files.list(Path.of(seasonFolderInfo.folder()))) {
    files.forEach(csvFilePath -> { ... });
}
```

### 5.5 Remove dead code

- `FedespPlayerAndResultsImportService.splitIntoFirstNameAndSecondName()` — never called.
- `FedespPracticionerInitialImportService.extractPracticionersNamesAndYears()` — never called; also contains a hardcoded debug `.contains("campos")` filter.

### 5.6 Document hardcoded competition metadata

Until a proper configuration or filename-parsing strategy is in place, add a `// TODO` comment on the hardcoded `"senior"`, `"nacional"`, `"esp"` values in `processSeasonFolder()` and a note in the FEDESP `AGENTS.md`.

---

## Summary Table

| Phase | Scope | Est. Effort | Primary Benefit |
|---|---|---|---|
| 0 — Baseline instrumentation | All 3 workflows | 0.5 day | Measurement foundation |
| 1 — Cut DB chattiness | All 3 workflows | 2–4 days | 60–80% IOPs reduction |
| 2 — Fix transactions + batch writes | Results + clubs/practicioners | 1–2 days | 15–20% additional throughput |
| 3 — Inference memoization + CPU | Results | 2–3 days | 20–40% CPU reduction |
| 4 — Stream rows | Results (primary) | 2–3 days | Memory + GC improvement |
| 5 — Robustness + hygiene | All 3 workflows | 0.5–1 day | Operational reliability |

## Cross-Adapter Notes

- Phases 1.4 (pre-fetch existing entities) and 3.3 (pre-filter in similarity services) require changes in the `shared` module (`PracticionerNameSimilarityService`, `ClubNameGrouppingService`). Before merging, verify BCNESA workflows are unaffected.
- Phase 2.2 (Hibernate batch config) is set in `runtime` `application.properties` and applies to all adapters automatically.
- Phase 3.4 (normalize consistency) requires aligning `FedespPlayerAndResultsImportService.normalize()` with the shared `NameNormalizer` — coordinate with any equivalent normalization changes in BCNESA.

