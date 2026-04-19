# Executive Summary - FEDESP Workflow Performance Analysis

**Analysis Date**: 2026-04-06  
**Scope**: All three FEDESP import workflows — clubs, practicioners, results  
**Goal**: Identify performance bottlenecks, estimate their cost, and define a phased mitigation plan  

---

## TL;DR

All three FEDESP import workflows share the same structural bottlenecks found in their BCNESA counterparts, **plus a set of FEDESP-specific risks** that compound the impact. The dominant cost drivers are:

1. **~17–18 DB round-trips per row** in the results import (N+1 query explosion).
2. **O(n²) fuzzy-matching clustering** in the practicioners and clubs imports.
3. **Full in-memory row materialization** before any processing begins.
4. **Ineffective transaction boundaries** — `@Transactional` has no effect on current call paths.
5. **FEDESP-only fragility**: `RuntimeException` on ANY non-matching filename, silent date-fallback to `now()`, hardcoded competition metadata.

---

## Per-Workflow Estimated Impact

| Workflow | Dominant Cost | DB IOPs (est.) | Wall Time (est.) |
|---|---|---|---|
| **Results** | N+1 per row × 17–18 calls | ~400–550k per season | 10–30 min |
| **Practicioners** | O(n²) fuzzy match + N+1 saves | ~500–700 IOPs | 7–25 s |
| **Clubs** | O(n²) club grouping + N+1 saves | ~150–250 IOPs | 3–10 s |

> Estimates assume a moderate FEDESP season (~1,500–2,000 processed rows, ~300–400 distinct practicioners, ~80 distinct club names). Scales linearly with season size.

---

## Cross-Cutting FEDESP-Specific Risks

| Risk | Location | Severity |
|---|---|---|
| `RuntimeException` for any non-matching file in season folder | `FedespMatchResultDetailsByLineIterator.processSeasonFolder()` | High |
| Date fallback to `ZonedDateTime.now()` on parse error | `FedespCsvFileRowInfoExtractor.parseZonedDateTime()` | High |
| Hardcoded competition metadata (`"senior"`, `"nacional"`, `"esp"`) | `FedespMatchResultDetailsByLineIterator.processSeasonFolder()` | Medium |
| `normalize()` in results service is inconsistent with shared `NameNormalizer` | `FedespPlayerAndResultsImportService.normalize()` | Medium |
| Practicioner inference has no minimum similarity threshold | `FedespPlayerAndResultsImportService.inferPracticionerByName()` | Medium |
| Dead code: `splitIntoFirstNameAndSecondName()` and `extractPracticionersNamesAndYears()` | Both services | Low |
| `Files.list()` streams not closed with try-with-resources | `FedespMatchResultDetailsByLineIterator`, `FedespCsvRepositoryFinderService` | Low–Medium |

---

## Recommended Optimization Phases

| Phase | Focus | Est. Effort | Expected IOPs Reduction |
|---|---|---|---|
| **Phase 0** | Baseline instrumentation | 0.5 day | 0% (measuring) |
| **Phase 1** | In-memory caches, remove N+1 lookups | 2–4 days | 60–80% |
| **Phase 2** | Fix transactions + batch writes | 1–2 days | 15–20% additional |
| **Phase 3** | Inference memoization + normalized key caches | 2–3 days | 20–40% CPU reduction |
| **Phase 4** | Streaming rows, remove full materialization | 2–3 days | Memory + GC improvement |
| **Phase 5** | I/O robustness, log hygiene, dead-code removal | 0.5–1 day | Operational reliability |

See `PHASED_OPTIMIZATION_PLAN.md` for full details.  
See individual analysis documents for finding-level breakdowns:
- [`PERFORMANCE_ANALYSIS_FEDESP_RESULTS_IMPORT.md`](./PERFORMANCE_ANALYSIS_FEDESP_RESULTS_IMPORT.md)
- [`PERFORMANCE_ANALYSIS_FEDESP_PRACTICIONERS_IMPORT.md`](./PERFORMANCE_ANALYSIS_FEDESP_PRACTICIONERS_IMPORT.md)
- [`PERFORMANCE_ANALYSIS_FEDESP_CLUBS_IMPORT.md`](./PERFORMANCE_ANALYSIS_FEDESP_CLUBS_IMPORT.md)

