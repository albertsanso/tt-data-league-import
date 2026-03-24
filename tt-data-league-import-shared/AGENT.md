# AGENT Guide - tt-data-league-import-shared

## Module purpose
- Shared importer primitives used by both CSV adapters.
- Provides generic iteration/import scaffolding plus fuzzy-name grouping utilities.
- Acts as the cross-adapter contract layer for row iteration and name clustering.

## Build and dependency position
- Maven artifact: `tt-data-league-import-shared`.
- Depends on:
  - `tt-data-league-core-domain`
  - `tt-data-league-core-repository-jpa`
  - `com.opencsv:opencsv`
- Consumed by adapter modules (`bcnesa` and `fedesp`).

## Owned code and responsibilities
- Generic file iteration:
  - `service/MatchResultDetailsByLineIterator.java`
  - `service/LineByLineInitialImportService.java`
- Progress tracking:
  - `service/CompletionTracker.java`
- Grouping/similarity:
  - `service/ClubNameGrouppingService.java`
  - `service/PracticionerNameGrouppingService.java`
  - `service/PracticionerNameSimilarityService.java`
  - `service/name/*` (`NameSimilarity`, `NameNormalizer`, `SoftLevenshtein`, `Levenshtein`)
- Shared records:
  - `model/ClubNameAndYearInfo.java`
  - `model/PracticionerNameAndYearInfo.java`
  - `model/MatchInfoKey.java`

## Agent boundaries
- Change this module for reusable logic needed by more than one adapter.
- Keep this module adapter-agnostic:
  - No adapter-specific file naming assumptions.
  - No runtime orchestration decisions.
- Avoid coupling to one federation format.

## Key behavior notes
- `MatchResultDetailsByLineIterator` is queue-driven and handles file-to-file reader transitions.
- `LineByLineInitialImportService.fetchCsvRowInfos()` materializes all rows in memory.
- Name grouping uses adaptive thresholds and token-level fuzzy matching.
- Existing class names use project spelling (`Practicioner`, `Groupping`); keep compatibility unless a coordinated rename is planned.

## Risks and pitfalls
- Full materialization can be memory heavy on large datasets.
- Similarity thresholds are heuristic and can over-merge or under-merge names.
- Progress tracker assumes non-zero totals; callers should avoid `total = 0`.

## Safe change rules for agents
1. Preserve public/protected contracts used by adapter services.
2. Keep generics and iterator lifecycle semantics stable (`reset`, `hasNext`, `next`, `close`).
3. If tuning matching thresholds, document rationale and expected effect.
4. Add tests when changing matching or dedup behavior.

## Typical change workflow
1. Identify cross-adapter duplication in adapter modules.
2. Extract/adjust reusable logic in `shared` with minimal API changes.
3. Update adapter callers in both modules.
4. Validate from repo root: `mvn -pl tt-data-league-import-shared -am test`.

## Gaps to keep in mind
- No tests currently under `src/test/java` in this module.
- `service/name/Main.java` appears to be a local utility/demo entrypoint, not production orchestration.

