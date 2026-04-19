# Executive Summary - BCNESA Results Import Performance

## What was analyzed
The flow behind `bcnesaPlayerAndResultsInitialImportService.processForSeason(baseFolder, season)`.

## Main performance issues
1. **DB IOP explosion per row**
   - Current flow performs many read-then-save repository operations for both local and visitor players.
   - Roughly ~17-18 DB calls per processed row in worst/common path.
2. **Transaction boundary not active as intended**
   - `@Transactional` is on a private self-invoked method, so proxy interception does not apply.
3. **Heavy CPU matching cost**
   - Club and practicioner inference scans full in-memory lists per player.
   - Practicioner similarity uses token-based Levenshtein-like scoring repeatedly.
4. **Memory pressure from full row materialization**
   - Entire season rows are loaded into memory before processing starts.
5. **Operational overhead**
   - Stack trace logging in row loop can become a throughput killer on bad data.

## Highest ROI actions
1. Cache entity lookups for the run (`Club`, `Practicioner`, `ClubMember`, `SeasonPlayer`, `SeasonPlayerResult`).
2. Save only when changed/new (avoid redundant updates).
3. Move transaction annotation to an actually proxied boundary and batch flush writes.
4. Memoize inference by repeated input strings.
5. Stream rows directly from iterator (instead of full list), once DB hot path is optimized.

## Expected impact
- **Phases 1-2** (DB + transaction tuning): biggest reduction in IOPs and runtime.
- **Phase 3** (CPU inference caching): strong CPU drop on real datasets with repeated names.
- **Phase 4** (streaming): improved memory profile and better scalability.

## Deliverables
- Deep technical report: `performance-plans/results/PERFORMANCE_ANALYSIS_BCNESA_RESULTS_IMPORT.md`
- Phased plan: `performance-plans/results/PHASED_OPTIMIZATION_PLAN.md`

