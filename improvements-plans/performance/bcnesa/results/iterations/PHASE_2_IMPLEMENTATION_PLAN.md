# Phase 2 Implementation Plan - BCNESA Results Import

## Goal

Phase 1 reduced DB chattiness via preloaded maps, get-or-create caches, and conditional saves. A critical bug emerged: exception swallowing in `getOrCreateClubMember()` and `getOrCreateSeasonPlayer()` allows partially-constructed, un-persisted JPA entities to be cached and passed downstream, causing `season_player_result` FK violations. Phase 2 fixes the exception model, installs real per-row transaction semantics via a delegate `@Component`, adds explicit `EntityManager.flush()` calls to guarantee FK insert ordering, and documents Hibernate batch settings that are now effective.

---

## Scope and Constraints

| Constraint | Detail |
|---|---|
| Module boundary | `tt-data-league-import-bcnesa-csv-adapter` only |
| No changes to `shared` | No new shared base classes or abstractions |
| No changes to `runtime` | Application config (`application.yml`) already correct |
| No changes to external domain/repo contracts | `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult` are external boundaries |
| Idempotent import behavior | Must be preserved; no duplicate entities on rerun |
| Project spelling | `Practicioner` (not Practitioner), `Groupping` (not Grouping) |
| Java version | Java 21 records; Spring Boot CGLIB proxies |

---

## Root Cause Analysis

### RC1 — `@Transactional` on private method is a no-op

```java
@Transactional
private void processMatchResultsDetailsRowInfoTransactional(...) { ... }
```

Spring AOP works by generating a CGLIB subclass proxy. Private methods cannot be overridden by subclasses, so the proxy never intercepts this call. The annotation is silently ignored. Every `repository.save()` inside runs in its own Spring Data auto-committed mini-transaction — one commit per `save()`, with no rollback grouping.

### RC2 — Exception swallowing returns un-persisted entities

In `getOrCreateClubMember()` and `getOrCreateSeasonPlayer()`, the catch block prints the error but does not re-throw. The critical issue is that the entity variable is assigned *before* the `save()` call via `createNew()`, so when `save()` throws:

1. The exception is caught and printed.
2. The entity variable holds a valid Java object with a JVM-generated UUID, but no DB row.
3. `return clubMember` / `return seasonPlayer` returns this ghost entity.
4. The ghost entity is passed downstream.
5. `seasonPlayerResultRepository.save()` references the ghost `seasonPlayer` UUID → FK violation: `(season_player_id)=(...) is not present in table "season_player"`.

The outer loop already catches row-level exceptions and increments `rowExceptions`, but the inner catch intercepts them first, preventing this safety net from activating.

**Cascade chain**:
```
ClubMember.save() throws → caught → ghost ClubMember (UUID not in DB) in cache
  → SeasonPlayer.save() throws (FK to club_member_id) → caught → ghost SeasonPlayer in cache
    → SeasonPlayerResult.save() throws (FK to season_player_id) ← THIS IS THE OBSERVED ERROR
```

### RC3 — Hibernate batch settings ineffective without real transactions

`hibernate.jdbc.batch_size: 50`, `order_inserts: true`, `order_updates: true` are already configured in `application.yml`. Without a spanning transaction, each `save()` commits immediately; Hibernate has no batch to accumulate. These settings only become effective when wrapping transactions are real and span multiple `save()` calls.

---

## Work Breakdown

---

### 2.0 — Critical Bug Fix: Exception Propagation

**File**: `BcnesaPlayerAndResultsInitialImportService.java`

**Sub-steps**:

1. In `getOrCreateClubMember()`: **remove the entire `try { ... } catch (Exception e) { ... }` block**. The method body should execute without any catch. Any `DataIntegrityViolationException`, `JpaObjectRetrievalFailureException`, or `DataAccessException` from `clubMemberRepository.save()` will propagate naturally.

2. In `getOrCreateSeasonPlayer()`: **remove the entire `try { ... } catch (Exception e) { ... }` block** identically.

3. In `createSeasonPlayerAndResultsForClub()`: After the `getOrCreateClubMember()` call, add an explicit null guard:
   ```java
   ClubMember clubMember = getOrCreateClubMember(...)
   if (clubMember == null) { metrics.rowsSkippedNullEntity++; return null; }
   ```
   After `getOrCreateSeasonPlayer()`, add the same pattern:
   ```java
   SeasonPlayer seasonPlayer = getOrCreateSeasonPlayer(...)
   if (seasonPlayer == null) { metrics.rowsSkippedNullEntity++; return null; }
   ```
   These null guards are defensive only — after removing the catch blocks, null returns will not occur in the normal exception path. They protect against future regressions.

4. The outer loop in `processMatchResultsDetailsInfo()` catches all exceptions from the row-processing call and increments `metrics.rowExceptions`. This is the intended failure boundary. No changes needed there — it now activates correctly.

5. The redundant double-check `if (inferredClub != null && inferredPracticioner != null)` can be left as-is (defensive). Leave it to avoid scope creep.

> **Note**: This step alone (2.0) eliminates the FK violation. Steps 2.1–2.2 address the transaction and batching improvements from the original Phase 2 plan.

---

### 2.1 — Fix Transaction Boundary with a Delegate Bean

**Files to create**:
- `...service/delegate/BcnesaRowProcessingTransactionalDelegate.java`

**Files to extract** (promote from private inner class to standalone):
- `...service/ImportRunContext.java` — extract from `BcnesaPlayerAndResultsInitialImportService`, make `public`
- `...service/ImportMetrics.java` — same

**Sub-steps**:

1. Extract `ImportRunContext` from its private inner class position to a standalone `public` class in the `service` package. Keep all fields and constructor unchanged.

2. Extract `ImportMetrics` to a standalone `public` class in the `service` package. Include the new `transactionRollbacks`, `entityManagerFlushCount`, and `rowsSkippedNullEntity` fields added in step 2.5.

3. Create `BcnesaRowProcessingTransactionalDelegate` in package `...player_single_match.service.delegate`:
   - Annotate with `@Component`
   - Inject via constructor: all five repositories, `BcnesaCsvFileRowInfoExtractor`, `EntityManager`
   - `LevenshteinDistance levenshtein` moves from the service as a `final` field
   - Expose one public method:
     ```java
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public void processRow(
         BcnesaMatchResultsDetailCsvFileRowInfo rowInfo,
         List<Club> allClubsList,
         List<Practicioner> allPracticionersList,
         ImportRunContext context,
         ImportMetrics metrics)
     ```
   - Move the full body of `processMatchResultsDetailsRowInfo()` into this method.
   - Move the following private helpers into the delegate:
     - `createMatchInfoKey()`, `createUniqueRowId()`, `createPlayersSingleMatchIfNotExists()`
     - `createSeasonPlayerAndResultsAsLocal()`, `createSeasonPlayerAndResultsAsVisitor()`
     - `createSeasonPlayerAndResults()`, `inferClubByTeamName()`, `inferPracticionerByName()`
     - `createSeasonPlayerAndResultsForClub()`, `getOrCreateSeasonPlayerResult()`
     - `getOrCreateClubMember()`, `getOrCreateSeasonPlayer()`
     - `buildPlayersPairingKey()`, `findClubInPreloadedMap()`, `findPracticionerInPreloadedMap()`
     - `ensureClubMemberSeasonRange()`, `normalize()`, `normalizeId()`, `normalizePersonName()`
     - `splitIntoFirstNameAndSecondName()`
   - All moved helpers are `private` within the delegate.

4. In `BcnesaPlayerAndResultsInitialImportService`:
   - Add `BcnesaRowProcessingTransactionalDelegate delegate` as the last constructor parameter and store as a field.
   - In `processMatchResultsDetailsInfo()`, replace the call to `processMatchResultsDetailsRowInfoTransactional(...)` with `delegate.processRow(rowInfo, allClubsList, allPracticionersList, context, metrics)`.
   - Remove now-empty `processMatchResultsDetailsRowInfoTransactional()` and `processMatchResultsDetailsRowInfo()`.
   - Remove all moved helper methods from the service.
   - Remove `LevenshteinDistance levenshtein` field from the service (moves to delegate).
   - **Keep in service**: `processMatchResultsDetailsInfo()`, `buildClubLookupMap()`, `buildPracticionerLookupMap()`, `printImportMetrics()`, `importMatchResultsDetailsInfo()`, `processForSeason()`, `processForAllSeasons()`.

**Why `REQUIRES_NEW` over `REQUIRED`**:

| Strategy | Behavior with no outer `@Transactional` | Behavior if outer `@Transactional` added later |
|---|---|---|
| `REQUIRED` | Creates new transaction (works now) | Joins service transaction; failed row poisons whole run |
| `REQUIRES_NEW` | Always creates independent transaction | Always creates separate transaction; failed row rolls back only itself |

`REQUIRES_NEW` is the correct choice: a failed row's rollback must not affect previously committed rows.

**Cache compatibility with `REQUIRES_NEW`**:

After each row's `REQUIRES_NEW` transaction commits, Hibernate closes the associated `EntityManager`. Entities stored in `ImportRunContext` caches become **detached**. Analysis:

| Cache | Fields accessed on cached entity | Safe with detachment? | Condition |
|---|---|---|---|
| `clubMemberCache` | `.getId()`, `.getClub().getId()` | ✅ if `ClubMember.club` is `@ManyToOne` (JPA default: EAGER) | Verify domain model |
| `seasonPlayerCache` | `.getClubMember().getClub().getId()`, `.getLicense().id()` | ✅ if `SeasonPlayer.clubMember` is EAGER and `License` is `@Embeddable` | Verify domain model |
| `seasonPlayerResultCache` | `.getId()`, `.getSeasonPlayer().getLicense().id()` | ✅ if `SeasonPlayerResult.seasonPlayer` is EAGER and `License` is `@Embeddable` | Verify domain model |

If any `@ManyToOne` association is overridden to `LAZY`, a `LazyInitializationException` will occur on the next cache hit after the first row commits. See Risk R1 in the risks table.

---

### 2.2 — Flush Ordering Within Per-Row Transaction

**File**: `BcnesaRowProcessingTransactionalDelegate.java`

**Problem**: With `order_inserts: true`, Hibernate collects all pending INSERTs within a transaction and reorders them by entity type before sending to JDBC. If all four entity types (`ClubMember`, `SeasonPlayer`, `SeasonPlayerResult`, `PlayersSingleMatch`) are pending at flush time, and Hibernate's ordering does not match the FK dependency chain, FK violations occur.

**FK dependency chain**:
```
ClubMember → SeasonPlayer → SeasonPlayerResult → PlayersSingleMatch
(club_member.id)  (club_member_id)  (season_player_id)  (spr_local_id, spr_visitor_id)
```

**Fix**: Inject `EntityManager` into the delegate via `@PersistenceContext`. Place explicit `entityManager.flush()` calls after each new entity save:

```
getOrCreateClubMember():
  if ClubMember newly created and saved:
    → entityManager.flush()          ← ClubMember row exists before SeasonPlayer INSERT
    → metrics.entityManagerFlushCount++
  if ensureClubMemberSeasonRange() saves ClubMember:
    → entityManager.flush()          ← updated ClubMember visible before SeasonPlayer INSERT
    → metrics.entityManagerFlushCount++

getOrCreateSeasonPlayer():
  if SeasonPlayer newly created and saved:
    → entityManager.flush()          ← SeasonPlayer row exists before SeasonPlayerResult INSERT
    → metrics.entityManagerFlushCount++

getOrCreateSeasonPlayerResult():
  if SeasonPlayerResult newly created and saved:
    → entityManager.flush()          ← SeasonPlayerResult rows exist before PlayersSingleMatch INSERT
    → metrics.entityManagerFlushCount++

createPlayersSingleMatchIfNotExists():
  → no explicit flush; commit at transaction end handles it
```

**Flush ordering diagram**:

```
REQUIRES_NEW transaction scope for one row
─────────────────────────────────────────────────────────────────────
Step 1: getOrCreateClubMember()
   ├─ [cache hit]  → skip        (no DB write, no flush)
   └─ [cache miss] → find or create
         ├─ [found in DB] → ensureSeasonRange → UPDATE + flush①
         └─ [new]         → INSERT + flush①  → ensureSeasonRange → UPDATE + flush②

Step 2: getOrCreateSeasonPlayer()
   ├─ [cache hit]  → skip        (no DB write, no flush)
   └─ [cache miss] → find or create
         ├─ [found in DB] → no INSERT
         └─ [new]         → INSERT + flush③
                             (FK club_member_id guaranteed in DB by flush① or flush②)

Step 3: getOrCreateSeasonPlayerResult()  (×2: local + visitor)
   ├─ [cache hit]  → skip        (no DB write, no flush)
   └─ [cache miss] → find or create
         ├─ [found in DB] → no INSERT
         └─ [new]         → INSERT + flush④
                             (FK season_player_id guaranteed in DB by flush③)

Step 4: createPlayersSingleMatchIfNotExists()
   ├─ [found] → skip
   └─ [new]   → INSERT (no explicit flush; commits at tx end)
                 (FKs to spr_local_id + spr_visitor_id guaranteed by flush④)

REQUIRES_NEW commit → remaining dirty state flushed by Hibernate
─────────────────────────────────────────────────────────────────────
```

> Flush calls ①–④ execute only when a new entity is actually saved. All four are skipped on a fully-cached re-run, so idempotency overhead is near zero.

---

### 2.3 — Hibernate Batch Settings Verification

**No code changes in this step.** Configuration in `application.yml` (runtime module) is already correct:

```yaml
jpa:
  properties:
    hibernate:
      jdbc:
        batch_size: 50
      order_inserts: true
      order_updates: true
```

With real per-row `REQUIRES_NEW` transactions, these settings now activate within each row's transaction. The practical batching benefit per row is small (at most ~4 entity saves per row — the batch of 50 is never filled from a single row). Larger batching benefit would require multi-row transactions — deferred to a future phase. Document this in the module `AGENTS.md`.

---

### 2.4 — Cache Compatibility After Transaction Boundaries

**No new code in this step.** This is a verification and documentation step.

**Actions**:

1. Check the compiled JAR or sibling repo source (`tt-data-league-core-domain`) for `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult` entity definitions.

2. Confirm `@ManyToOne` associations on `ClubMember.club`, `SeasonPlayer.clubMember`, `SeasonPlayerResult.seasonPlayer` have no `fetch = FetchType.LAZY` override. JPA default for `@ManyToOne` is `EAGER`.

3. Confirm `License` is `@Embeddable` (not a separate `@Entity`). If embedded, it is always loaded with the parent entity and is safe from detachment.

4. If lazy associations are found: add a merge step in the delegate at the top of `processRow()` to re-attach cached entities before use:
   ```java
   // Re-attach detached cached ClubMember example:
   ClubMember attached = entityManager.contains(cached) ? cached : entityManager.merge(cached);
   ```
   Or store only UUIDs in caches and re-fetch the entity at the start of each delegate call (more expensive but eliminates detachment risk entirely).

5. Document findings in `AGENTS.md` under a new "Phase 2 transaction notes" section.

---

### 2.5 — Update Metrics for Phase 2

**File**: `ImportMetrics.java` (newly extracted standalone class)

Add three new counters:
```java
long transactionRollbacks;       // incremented in outer loop catch when delegate throws
long entityManagerFlushCount;    // incremented at each explicit em.flush() call
long rowsSkippedNullEntity;      // incremented in null guard after get-or-create
```

Update `printImportMetrics()` in `BcnesaPlayerAndResultsInitialImportService` to include a new Phase 2 summary line:
```
BCNESA Phase2 tx: rollbacks={transactionRollbacks}, emFlushes={entityManagerFlushCount}, skippedNull={rowsSkippedNullEntity}
```

> `transactionRollbacks` is incremented by the outer loop's catch block in the service (not the delegate), because `REQUIRES_NEW` rollbacks surface as exceptions from `delegate.processRow()`.

---

### 2.6 — Tests for Phase 2

**File to create**: `...service/delegate/BcnesaRowProcessingTransactionalDelegatePhase2Test.java`

**Test cases**:

1. `shouldPropagateExceptionWhenClubMemberSaveThrows`
   - Mock `clubMemberRepository.findByPracticionerIdAndClubId()` → empty
   - Mock `clubMemberRepository.save()` → throw `DataIntegrityViolationException`
   - Call `delegate.processRow(...)` with valid row info and inferred club/practicioner
   - Assert exception propagates out (not swallowed)
   - Verify `seasonPlayerRepository.save()` **never** called

2. `shouldPropagateExceptionWhenSeasonPlayerSaveThrows`
   - Mock `clubMemberRepository` → returns valid `ClubMember`
   - Mock `seasonPlayerRepository.findByPracticionerIdClubIdSeason()` → empty
   - Mock `seasonPlayerRepository.save()` → throw `DataIntegrityViolationException`
   - Assert exception propagates
   - Verify `seasonPlayerResultRepository.save()` **never** called

3. `shouldSkipRowCleanlyWhenNullGuardActivates`
   - Force a null return from `getOrCreateClubMember()` path (by making inference return a non-matching club so `findClubInPreloadedMap` returns empty)
   - Verify `metrics.rowsSkippedInferenceMiss > 0` and no repository `save()` calls

4. `shouldCompleteFullRowProcessingWhenAllRepositoriesSucceed`
   - All `find` calls return empty, all `save` calls succeed
   - Verify each repository's `save()` called exactly once in the order: ClubMember → SeasonPlayer → SeasonPlayerResult (×2) → PlayersSingleMatch
   - Verify `metrics.rowsProcessed == 1`

**File to update**: `BcnesaPlayerAndResultsInitialImportServicePhase1Test.java`

Changes:
1. Add `BcnesaRowProcessingTransactionalDelegate delegate = mock(...)` to test fields.
2. Pass mock delegate as additional constructor argument in `setUp()`.
3. Existing tests directly verify repository interactions — after Phase 2 the service no longer calls repositories. **Preferred migration**: simplify existing Phase 1 tests to verify `delegate.processRow()` is called N times per row. Move repository-interaction assertions to `BcnesaRowProcessingTransactionalDelegatePhase2Test`.
4. Add test: `shouldIncrementTransactionRollbacksWhenDelegateThrows`
   - Mock `delegate.processRow()` → throw `RuntimeException`
   - Invoke `processMatchResultsDetailsInfo()` via reflection with a 2-row list
   - Verify both rows' exceptions are caught and `metrics.rowExceptions == 2`

---

## Files to Create and Modify

### New files

| Path | Purpose |
|---|---|
| `...service/delegate/BcnesaRowProcessingTransactionalDelegate.java` | Spring `@Component`; `@Transactional(REQUIRES_NEW)` `processRow()`; `EntityManager` flush calls |
| `...service/ImportRunContext.java` | Extracted from inner class; holds caches and preloaded maps |
| `...service/ImportMetrics.java` | Extracted from inner class; holds timing, counters, Phase 2 fields |
| `...service/delegate/BcnesaRowProcessingTransactionalDelegatePhase2Test.java` | Unit tests for delegate exception propagation and happy path |

### Modified files

| Path | Changes |
|---|---|
| `...service/BcnesaPlayerAndResultsInitialImportService.java` | Remove moved helpers; add delegate field and constructor param; replace transactional call; update metrics log |
| `...service/BcnesaPlayerAndResultsInitialImportServicePhase1Test.java` | Add delegate mock to setUp; migrate repository-interaction tests; add rollback counter test |
| `tt-data-league-import-bcnesa-csv-adapter/AGENTS.md` | Document delegate pattern, transaction model, cache detachment notes |

---

## Data Structures / Interfaces

### Delegate constructor signature

```java
@Autowired
public BcnesaRowProcessingTransactionalDelegate(
    ClubMemberRepository clubMemberRepository,
    SeasonPlayerRepository seasonPlayerRepository,
    SeasonPlayerResultRepository seasonPlayerResultRepository,
    PlayersSingleMatchRepository playersSingleMatchRepository,
    BcnesaCsvFileRowInfoExtractor rowInfoExtractor
    // EntityManager injected via @PersistenceContext field, not constructor
)
```

`EntityManager` should be injected via `@PersistenceContext` annotation on a field (not constructor) to ensure Spring binds the correct transaction-scoped instance.

### `ImportRunContext` (extracted, public)

```java
public class ImportRunContext {
    final Map<String, Club> clubsByNameMap;
    final Map<String, Practicioner> practicionersByNameMap;
    final Map<ClubMemberCacheKey, ClubMember> clubMemberCache = new HashMap<>();
    final Set<ClubMemberCacheKey> clubMemberSeasonRangeUpdated = new HashSet<>();
    final Map<SeasonPlayerCacheKey, SeasonPlayer> seasonPlayerCache = new HashMap<>();
    final Map<SeasonPlayerResultCacheKey, SeasonPlayerResult> seasonPlayerResultCache = new HashMap<>();

    public ImportRunContext(Map<String, Club> clubsByNameMap, Map<String, Practicioner> practicionersByNameMap) { ... }
}
```

### `ImportMetrics` (extracted, public, Phase 2 additions)

```java
public class ImportMetrics {
    // Phase 1 fields (unchanged):
    long rowsTotal, rowsProcessed, rowsSkippedPlayerD, rowsSkippedInferenceMiss;
    long rowExceptions, inferenceMisses;
    long clubLookupHit, clubLookupMiss, practicionerLookupHit, practicionerLookupMiss;
    long clubMemberCacheHit, clubMemberCacheMiss;
    long seasonPlayerCacheHit, seasonPlayerCacheMiss;
    long seasonPlayerResultCacheHit, seasonPlayerResultCacheMiss;
    long playersSingleMatchCacheHit, playersSingleMatchCacheMiss;
    long clubMemberSaved, seasonPlayerSaved, seasonPlayerResultSaved, playersSingleMatchSaved;
    long saveSkippedNoChange;
    long preloadMs, rowLoopMs, totalMs;

    // Phase 2 additions:
    long transactionRollbacks;       // rows whose REQUIRES_NEW tx rolled back
    long entityManagerFlushCount;    // total explicit em.flush() calls
    long rowsSkippedNullEntity;      // defensive null guard activations
}
```

---

## Processing Flow Pseudocode

### Service (`processMatchResultsDetailsInfo`)

```
preload clubs and practicioners (findAll)
build ImportRunContext(clubsByNameMap, practicionersByNameMap)
build ImportMetrics()

for each rowInfo in matchResultsDetailCsvFileRowInfoList:
    try:
        delegate.processRow(rowInfo, allClubsList, allPracticionersList, context, metrics)
        // REQUIRES_NEW: commits independently per row
    catch Exception:
        metrics.transactionRollbacks++
        metrics.rowExceptions++
        log error + stack trace
    finally:
        completionTracker.trackIncrement()

printImportMetrics(metrics, context)
```

### Delegate (`processRow`)

```
@Transactional(REQUIRES_NEW)
processRow(...):
    metrics.rowsTotal++
    extract rowInfo
    if playerLetter == "D": metrics.rowsSkippedPlayerD++; return

    localResult  = createSeasonPlayerAndResults(localPlayer,   LOCAL,   ...)
    visitorResult = createSeasonPlayerAndResults(visitorPlayer, VISITOR, ...)

    if localResult == null OR visitorResult == null:
        metrics.rowsSkippedInferenceMiss++
        return

    createPlayersSingleMatchIfNotExists(localResult, visitorResult, ...)
    metrics.rowsProcessed++

createSeasonPlayerAndResultsForClub():
    clubMember = getOrCreateClubMember(...)       // throws on save failure
    if clubMember == null: return null            // defensive only
    seasonPlayer = getOrCreateSeasonPlayer(...)   // throws on save failure
    if seasonPlayer == null: return null          // defensive only
    return getOrCreateSeasonPlayerResult(...)

getOrCreateClubMember():
    // NO try-catch
    if cached: return cached
    find in repo
    if absent: create new + save + em.flush() + metrics.entityManagerFlushCount++
    ensureClubMemberSeasonRange → if saved: em.flush() + metrics.entityManagerFlushCount++
    put in cache; return

getOrCreateSeasonPlayer():
    // NO try-catch
    if cached: return cached
    find in repo
    if absent: create new + save + em.flush() + metrics.entityManagerFlushCount++
    put in cache; return

getOrCreateSeasonPlayerResult():
    // Already had no try-catch; add flush after new save
    if cached: return cached
    find in repo
    if absent: create new + save + em.flush() + metrics.entityManagerFlushCount++
    put in cache; return
```

---

## Observability Additions

| Counter | Increment location | Meaning |
|---|---|---|
| `transactionRollbacks` | Service outer catch block | Rows whose `REQUIRES_NEW` tx rolled back (exception from delegate) |
| `entityManagerFlushCount` | Delegate after each `em.flush()` | Total explicit flush calls; 0 on full re-run confirms idempotency |
| `rowsSkippedNullEntity` | `createSeasonPlayerAndResultsForClub()` null guard | Defensive; should always be 0 |

**New log line** (append to `printImportMetrics()`):
```
BCNESA Phase2 tx: rollbacks={transactionRollbacks}, emFlushes={entityManagerFlushCount}, skippedNull={rowsSkippedNullEntity}
```

---

## Risks and Mitigations

| # | Risk | Probability | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Detached entity cache hit causes `LazyInitializationException` | Medium | High | Verify `ClubMember.club`, `SeasonPlayer.clubMember`, `SeasonPlayerResult.seasonPlayer` are EAGER; verify `License` is `@Embeddable`. If lazy, add `entityManager.merge()` on cache hit or cache IDs only. |
| R2 | Constructor change breaks Phase 1 test `setUp()` | High | Low | Update `BcnesaPlayerAndResultsInitialImportServicePhase1Test.setUp()` to pass mock delegate as last constructor argument |
| R3 | `ensureClubMemberSeasonRange()` flush adds unexpected flush count on re-run | Low | Low | `entityManagerFlushCount` metric makes this visible; acceptable overhead |
| R4 | `@PersistenceContext` EntityManager bound to wrong transaction scope | Low | High | Use default `@PersistenceContext` (TRANSACTION type) on the delegate — Spring correctly binds it to the active `REQUIRES_NEW` transaction |
| R5 | Moving all helpers to delegate creates a 600+ line class | Low | Medium | Acceptable for now; Phase 3 can decompose further if needed |
| R6 | `order_inserts: true` reorders pending entities even after explicit flush | Very Low | Medium | Explicit flush forces JDBC send of all pending entities; nothing remains pending after flush, so `order_inserts` has nothing to reorder |
| R7 | Re-run idempotency broken because `ensureClubMemberSeasonRange()` fires on every row for the same `ClubMember` | Low | Medium | `clubMemberSeasonRangeUpdated` set in `ImportRunContext` prevents repeated saves for the same key within one run; flush after save is skipped on subsequent hits |

---

## Test Coverage Checklist

### `BcnesaRowProcessingTransactionalDelegatePhase2Test`
- [ ] Exception from `clubMemberRepository.save()` propagates (not swallowed)
- [ ] Exception from `seasonPlayerRepository.save()` propagates
- [ ] `seasonPlayerRepository.save()` never called when `clubMemberRepository.save()` throws
- [ ] `seasonPlayerResultRepository.save()` never called when `seasonPlayerRepository.save()` throws
- [ ] Null guard in `createSeasonPlayerAndResultsForClub()` prevents downstream NPE
- [ ] Full happy path: all entities saved in order with correct arguments
- [ ] `metrics.rowsProcessed == 1` on successful row

### `BcnesaPlayerAndResultsInitialImportServicePhase1Test` (updated)
- [ ] `setUp()` includes mock delegate as constructor argument
- [ ] Service calls `delegate.processRow()` once per non-skipped row
- [ ] Exception thrown by `delegate.processRow()` increments `rowExceptions` and `transactionRollbacks`
- [ ] Service does NOT call any repository directly (all delegated)
- [ ] Inference-miss rows are still skipped inside the delegate (verify via mock delegate behavior)

### `SeasonPlayerResultCacheKeyTest` (no changes needed)
- [ ] Confirm no regression from inner class extraction of `ImportRunContext`/`ImportMetrics`

---

## Rollback Strategy

Deliver as independent commits so each can be reverted without touching the others:

1. **Commit A (2.0 — Bug fix only)**: Remove try-catch blocks from `getOrCreateClubMember()` and `getOrCreateSeasonPlayer()`. Add null guards. Add Phase 2 metric fields to `ImportMetrics`. Update `printImportMetrics()`.
   - **Risk**: Very low. Behavior change is that exceptions now propagate instead of being swallowed. The outer loop already handles them correctly.
   - **Validate**: Run import on clean dataset; confirm no FK violations. `rowExceptions` should be 0 on clean data.

2. **Commit B (2.1 + 2.5 — Extract classes)**: Extract `ImportRunContext` and `ImportMetrics` to standalone classes.
   - **Risk**: Very low (pure refactor). Verify compile and tests pass.

3. **Commit C (2.1 + 2.2 — Delegate + flush)**: Create `BcnesaRowProcessingTransactionalDelegate`. Move all helpers. Add `EntityManager` flush calls. Update service to use delegate.
   - **Risk**: Medium. If `LazyInitializationException` occurs on cached entities, revert to Commit B (bug fix is preserved).

4. **Commit D (2.6 — Tests)**: Update `BcnesaPlayerAndResultsInitialImportServicePhase1Test`. Add `BcnesaRowProcessingTransactionalDelegatePhase2Test`.
   - **Risk**: None. Tests only.

5. **Commit E (2.4 — Documentation)**: Update `AGENTS.md` with Phase 2 delegate pattern and cache detachment notes.
   - **Risk**: None. Documentation only.

---

## Validation Checklist

### Functional (post-deploy)
- [ ] Full season import completes without FK violation on `season_player_result`
- [ ] Immediate re-run produces zero new `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult`, `PlayersSingleMatch` saves (`saveSkippedNoChange` near total row count)
- [ ] `transactionRollbacks` counter is 0 on a clean dataset
- [ ] `rowsSkippedNullEntity` is 0 (defensive guard never activated)
- [ ] `rowExceptions` is 0 on a clean dataset

### Performance (compare to Phase 1 baseline)
- [ ] `entityManagerFlushCount` in logs: positive on first run (new entities), near 0 on re-run
- [ ] Total runtime should not regress more than 5% versus Phase 1 baseline (explicit flushes add one DB round-trip per new entity, but this replaces previously swallowed failures)
- [ ] DB statement count: with SQL logging, verify INSERT order is always `ClubMember → SeasonPlayer → SeasonPlayerResult → PlayersSingleMatch`

### Stability
- [ ] `rowExceptions` stays at 0 on a clean dataset
- [ ] No `LazyInitializationException` in logs (validates detached entity cache safety)
- [ ] Cache sizes at end of run match Phase 1 baseline

---

## Open Questions Before Implementation

1. **Lazy vs. eager fetch on domain entities**: Are `ClubMember.club`, `SeasonPlayer.clubMember`, `SeasonPlayerResult.seasonPlayer` confirmed EAGER in `tt-data-league-core-domain`? This determines whether cache detachment is safe or requires a mitigation in the delegate.

2. **Is `License` an `@Embeddable`?** Accessed as `seasonPlayer.getLicense().id()` on potentially-detached entities in `createUniqueRowId()`. If it is a separate `@Entity`, lazy-loading may fail on detached instances.

3. **Constructor parameter order for service**: Should `BcnesaRowProcessingTransactionalDelegate` be the first or last constructor parameter in `BcnesaPlayerAndResultsInitialImportService`? Recommendation: last, to minimise diff in existing test setUp calls.

---

## Suggested Commit Slicing

| # | Commit message | Files | Notes |
|---|---|---|---|
| 1 | `fix(bcnesa): remove exception swallowing in get-or-create methods; add null guards` | `BcnesaPlayerAndResultsInitialImportService.java` | **Highest priority** — resolves FK violation |
| 2 | `refactor(bcnesa): extract ImportRunContext and ImportMetrics to standalone classes; add Phase 2 counters` | `ImportRunContext.java` (new), `ImportMetrics.java` (new), `BcnesaPlayerAndResultsInitialImportService.java` | Pure refactor; verify compile |
| 3 | `feat(bcnesa): introduce BcnesaRowProcessingTransactionalDelegate with REQUIRES_NEW and EntityManager flush ordering` | `BcnesaRowProcessingTransactionalDelegate.java` (new), `BcnesaPlayerAndResultsInitialImportService.java` | Transaction model change; benchmark |
| 4 | `test(bcnesa): update Phase 1 test to mock delegate; add Phase 2 tests for exception propagation` | `BcnesaPlayerAndResultsInitialImportServicePhase1Test.java`, `BcnesaRowProcessingTransactionalDelegatePhase2Test.java` (new) | Test coverage |
| 5 | `docs(bcnesa): update AGENTS.md with Phase 2 delegate pattern and cache detachment notes` | `AGENTS.md` | Documentation maintenance |

