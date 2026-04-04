# Phased Optimization Plan - BCNESA Results Import

## Target
Reduce DB IOPs and import time for `BcnesaPlayerAndResultsInitialImportService.processForSeason(...)`.

## Principles
- Keep behavior idempotent.
- Prefer low-risk, high-impact changes first.
- Measure each phase before moving to next.

---

## Phase 0 - Instrument first (0.5-1 day)

### Objectives
- Build a baseline to avoid blind optimization.

### Actions
1. Add per-stage timers around:
   - file discovery
   - CSV read/materialization
   - row processing loop
   - inference time
   - DB save time
2. Enable SQL count logging in non-prod runs.
3. Record:
   - total rows
   - skipped rows (`playerLetter == D`)
   - inference misses
   - exception count

### Success criteria
- You can report exact total DB statements and ms per stage for one season run.

---

## Phase 1 - Cut DB chattiness (highest ROI, 2-4 days)

### 1.1 Cache existing entities once per season
**Current pain**
- Re-query club/practicioner by name for each player (`findByName`, `findByFullName`).

**Change**
- Build maps once:
  - `Map<String, Club> clubByName`
  - `Map<String, Practicioner> practicionerByFullName`
- Resolve from memory after inference.

**Expected impact**
- Remove ~4 DB reads per row.

### 1.2 Introduce in-memory get-or-create caches
**Current pain**
- `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult` use read-then-save every row.

**Change**
- Add maps keyed by natural keys:
  - `clubMemberKey(practicionerId, clubId)`
  - `seasonPlayerKey(practicionerId, clubId, season)`
  - `seasonPlayerResultKey(season, competition..., matchDay, playerLetter, pairingKey, teamRole, clubId)`
- Lookup cache first; hit DB only on cache miss.

**Expected impact**
- Major reduction in repetitive lookups.
- Typical workloads with repeated players can drop DB reads by an order of magnitude.

### 1.3 Save only when needed
**Current pain**
- `save` called even for already-existing entities.

**Change**
- Persist only if newly created or mutated.
- For existing `SeasonPlayerResult`, skip `save` when no changes are applied.

**Expected impact**
- Lower write IOPs and WAL/lock pressure.

---

## Phase 2 - Fix transaction model + batch writes (1-2 days)

### 2.1 Make transaction boundary real
**Current pain**
- `@Transactional` on private self-invoked method is ineffective.

**Change options**
1. Move row-processing into another Spring bean with `public @Transactional` method.
2. Or annotate the top-level processing method with explicit batching strategy.

### 2.2 Batch persistence and controlled flush
**Change**
- Configure Hibernate batching in runtime config:
  - `hibernate.jdbc.batch_size`
  - `hibernate.order_inserts=true`
  - `hibernate.order_updates=true`
- Flush/clear persistence context every N rows (e.g., 200-1000), tuned by memory.

**Expected impact**
- Fewer network round-trips and better DB throughput.

---

## Phase 3 - Reduce CPU inference cost (2-4 days)

### 3.1 Precompute normalized values
- Pre-normalize clubs once (`normalizedClubName`).
- Cache normalized player names for this run.

### 3.2 Add inference memoization
- `Map<String, Optional<Club>> inferredClubByTeamName`
- `Map<String, Optional<Practicioner>> inferredPracticionerByName`

This is high value because many names repeat throughout season files.

### 3.3 Add cheap pre-filters before expensive similarity
- Length-difference threshold.
- First-token/prefix check.

**Expected impact**
- Significant CPU reduction on large practicioner sets.

---

## Phase 4 - Stream rows instead of materializing all (2-3 days)

### Current pain
- `fetchCsvRowInfos()` loads full season into heap.

### Change
- Process rows directly from iterator loop.
- Keep only small working sets/caches in memory.

### Expected impact
- Better memory profile, less GC, faster time-to-first-write.

---

## Phase 5 - I/O robustness and log hygiene (0.5-1 day)

### Actions
1. Use try-with-resources for every `Files.list(...)` stream.
2. Replace stack-trace-per-row with:
   - concise row error summary
   - capped detailed samples (e.g., first 20)
   - final counters
3. Promote frequently allocated constants:
   - static `DateTimeFormatter` in extractor

---

## Suggested rollout order
1. Phase 0 baseline
2. Phase 1 DB cache + conditional saves
3. Phase 2 transactions + batch settings
4. Phase 3 inference optimization
5. Phase 4 streaming
6. Phase 5 hygiene

---

## KPI checklist per phase
- Total runtime (ms)
- DB statement count (total/read/write)
- Rows/sec
- Error rows and error rate
- Peak heap usage
- GC pause time

---

## Practical expected outcome
- After Phases 1-2: likely biggest gain (often 3x-10x depending on duplication and DB latency).
- Phase 3 adds CPU headroom for bigger practicioner catalogs.
- Phase 4 stabilizes memory and large-season scalability.

