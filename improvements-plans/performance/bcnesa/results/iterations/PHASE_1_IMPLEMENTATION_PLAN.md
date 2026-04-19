# Phase 1 Implementation Plan - BCNESA Results Import

## Goal
Reduce DB I/O and runtime in `BcnesaPlayerAndResultsInitialImportService.processForSeason(...)` by implementing Phase 1 from `PHASED_OPTIMIZATION_PLAN.md` with low-risk, idempotent changes.

## Scope and Constraints
- Module scope: `tt-data-league-import-bcnesa-csv-adapter` only.
- Keep behavior idempotent.
- Do not redesign transaction boundaries in this phase.
- Do not move federation-specific logic into `tt-data-league-import-shared`.

---

## Work Breakdown Checklist

### 1.1 Cache existing entities once per season
- [ ] Add season-run preload step in `BcnesaPlayerAndResultsInitialImportService`:
  - [ ] Build `Map<String, Club> clubByName` from `clubRepository.findAll()`.
  - [ ] Build `Map<String, Practicioner> practicionerByFullName` from `practicionerRepository.findAll()`.
- [ ] Normalize keys consistently (trim/lowercase + same normalization path currently used by inference).
- [ ] Replace per-row repository lookups (`findByName`, `findByFullName`) with in-memory map resolution.
- [ ] Add counters for lookup hits/misses.

**Expected impact**: remove repeated read queries for club/practicioner lookup (high row-level savings).

### 1.2 Introduce in-memory get-or-create caches
- [ ] Add BCNESA-local key records:
  - [ ] `ClubMemberCacheKey(practicionerId, clubId)`
  - [ ] `SeasonPlayerCacheKey(practicionerId, clubId, seasonRange)`
  - [ ] `SeasonPlayerResultCacheKey(...)` (all fields needed to match current natural uniqueness)
  - [ ] Optional: `PlayersSingleMatchCacheKey(...)`
- [ ] Add season-scoped maps in service:
  - [ ] `Map<ClubMemberCacheKey, ClubMember>`
  - [ ] `Map<SeasonPlayerCacheKey, SeasonPlayer>`
  - [ ] `Map<SeasonPlayerResultCacheKey, SeasonPlayerResult>`
  - [ ] Optional: `Map<PlayersSingleMatchCacheKey, PlayersSingleMatch>`
- [ ] Refactor get-or-create methods to use: cache -> repository -> create.
- [ ] Populate cache after resolve/create.

**Expected impact**: large drop in repetitive `find...` calls for repeated players/matches.

### 1.3 Save only when needed
- [ ] `ClubMember`: save only when created or mutated (e.g., season range added).
- [ ] `SeasonPlayer`: save only when created (or when mutation is actually required).
- [ ] `SeasonPlayerResult`: save only when created or modified.
- [ ] `PlayersSingleMatch`: keep idempotent existence behavior and save only on miss.
- [ ] Add safe row skip on unresolved local/visitor inference (counter + continue).

**Expected impact**: lower write load, reduced lock/WAL pressure.

---

## Files to Modify

### Main service
- `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/service/BcnesaPlayerAndResultsInitialImportService.java`

### New key models
- `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/model/ClubMemberCacheKey.java`
- `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/model/SeasonPlayerCacheKey.java`
- `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/model/SeasonPlayerResultCacheKey.java`
- Optional: `tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/model/PlayersSingleMatchCacheKey.java`

### Tests (minimal, feasible in this phase)
- `tt-data-league-import-bcnesa-csv-adapter/src/test/java/.../SeasonPlayerResultCacheKeyTest.java`
- `tt-data-league-import-bcnesa-csv-adapter/src/test/java/.../BcnesaPlayerAndResultsInitialImportServicePhase1Test.java`
- Possibly update `tt-data-league-import-bcnesa-csv-adapter/pom.xml` for JUnit 5/Mockito if not already available.

---

## Data Structures and Key Definitions

- `clubByName`: normalized club name -> `Club`
- `practicionerByFullName`: normalized full name -> `Practicioner`
- `ClubMemberCacheKey`: `(practicionerId, clubId)`
- `SeasonPlayerCacheKey`: `(practicionerId, clubId, seasonRange)`
- `SeasonPlayerResultCacheKey`: include fields currently composing logical uniqueness, aligned with repository query arguments.
- Optional `PlayersSingleMatchCacheKey`: use stable identity based on local/visitor result IDs plus row-level uniqueness token.

> Use Java `record` for keys to guarantee equality/hash behavior.

---

## Cache and Transaction Boundaries (Phase 1)

- Cache lifecycle: one season processing run only.
- Initialize caches and preload maps at start of season processing.
- Discard caches at end of method.
- Keep current transaction behavior unchanged in this phase.

---

## Processing Flow (Pseudocode)

```java
start timers and counters;
preload clubs/practicioners;
initialize season caches;

for each row:
  rowsTotal++;
  if playerLetter == "D":
    rowsSkippedPlayerD++;
    continue;

  infer local and visitor (from preloaded maps + existing inference rules);
  if local or visitor unresolved:
    rowsSkippedInferenceMiss++;
    continue;

  localClubMember = getOrCreateClubMemberCached(...);
  localSeasonPlayer = getOrCreateSeasonPlayerCached(...);
  localResult = getOrCreateSeasonPlayerResultCached(...);

  visitorClubMember = getOrCreateClubMemberCached(...);
  visitorSeasonPlayer = getOrCreateSeasonPlayerCached(...);
  visitorResult = getOrCreateSeasonPlayerResultCached(...);

  createPlayersSingleMatchIfNotExistsCached(...);
  rowsProcessed++;

log summary counters and timings;
```

---

## Observability for Phase 1 Verification

Track and log at season end:
- Rows: `rowsTotal`, `rowsProcessed`, `rowsSkippedPlayerD`, `rowsSkippedInferenceMiss`, `rowExceptions`
- Cache: hit/miss counters per cache type
- Persistence: save counts per entity, and `saveSkippedNoChange`
- Timing: preload ms, row-loop ms, total ms
- Optional: cache sizes at end of run

---

## Risks and Mitigations

1. Wrong cache key shape -> false hits
- Mitigation: keep key fields aligned with existing natural lookup criteria; add key equality tests.

2. Skipping save when mutation exists
- Mitigation: explicit change detection before save-skip.

3. Cache memory growth
- Mitigation: season-scoped caches only; monitor final cache sizes.

4. Behavior drift in inference failure paths
- Mitigation: keep existing inference logic; only replace repeated lookups with map access.

---

## Rollback Strategy

- Deliver in small commits (1.1, 1.2, 1.3 separated).
- Optional temporary flag in service to disable cache path quickly if needed.
- If issue appears, revert latest Phase 1 commit without undoing earlier instrumentation work.

---

## Validation Checklist

### Functional and idempotency
- [ ] First run imports expected rows without new errors.
- [ ] Immediate rerun does not create duplicates in `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult`, `PlayersSingleMatch`.
- [ ] `playersSingleMatchSaved` is near zero on rerun.

### Performance
- [ ] Baseline collected from current code for same season and DB snapshot.
- [ ] After Phase 1, compare runtime and DB statement counts.
- [ ] Confirm lower DB reads/writes per processed row.

### Stability
- [ ] No increase in inference misses or exception rate.
- [ ] Cache sizes stay within expected range for season size.

---

## Benchmark Protocol (Before/After)

1. Choose one fixed season dataset and DB snapshot.
2. Run importer once for warm-up (optional).
3. Run measured import and capture:
   - total runtime
   - row counters
   - read/write statement counts
4. Re-run same season immediately to confirm idempotency.
5. Repeat after Phase 1 with same environment.

Compare:
- runtime delta
- statements per row
- writes on rerun
- error/miss counters

---

## Suggested Commit Slicing

1. Add metrics counters/timers and end-of-run summary in BCNESA results service.
2. Implement 1.1 preloaded club/practicioner maps and switch row resolution to map lookups.
3. Add key records + cache-first get-or-create for `ClubMember` and `SeasonPlayer`.
4. Add cache-first get-or-create for `SeasonPlayerResult` and optional match dedupe cache.
5. Add 1.3 conditional-save logic and safe unresolved-row skip.
6. Add minimal tests for key equality and cache/no-save behavior.

