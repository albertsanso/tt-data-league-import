# AGENT Guide - tt-data-league-import-bcnesa-csv-adapter

## Module purpose
- Adapter for BCNESA CSV match exports.
- Walks season/competition folder hierarchy, parses row data, and persists mapped domain entities.
- Encodes BCNESA-specific competition taxonomy, folder naming, and row schema.

## Build and dependency position
- Maven artifact: `tt-data-league-import-bcnesa-csv-adapter`.
- Depends on:
  - `tt-data-league-core-domain`
  - `tt-data-league-core-repository-jpa`
  - `tt-data-league-import-shared`
  - `com.opencsv:opencsv`
- Loaded by runtime module for executable imports.

## Owned code and responsibilities
- Competition taxonomy and folder metadata:
  - `shared/model/competition/BcnesaCompetitionType.java`
  - `shared/model/competition/BcnesaCompetition.java`
  - `shared/model/fs/*`
- File-system discovery and iterator:
  - `shared/service/BcnesaCsvRepositoryFinderService.java`
  - `shared/service/BcnesaMatchResultDetailsByLineIterator.java`
- Row extraction:
  - `shared/service/BcnesaCsvFileRowInfoExtractor.java`
- Import services:
  - `club/service/BcnesaClubInitialImportService.java`
  - `club/service/BcnesaPracticionerInitialImportService.java`
  - `player_single_match/service/BcnesaPlayerAndResultsInitialImportService.java`

## Input contract (important)
- Expected folder hierarchy:
  - `{base}/{season}/{competitionType}/{competitionLevelFolder}/jornada{N}-g{G}.csv`
- Season folder names must pass `SeasonRangeValidator` (`YYYY-YYYY`).
- Match file names must match `jornada(\d+)-g(\d+)\.csv`.
- Extractor assumes fixed column positions for teams/players/licenses/scores/date.

## Agent boundaries
- Change this module for BCNESA-specific format/rule updates only.
- Cross-adapter abstractions belong in `tt-data-league-import-shared`.
- Runtime orchestration belongs in `tt-data-league-import-runtime`.

## Key behavior notes
- Iterator enriches each row with metadata (season/type/category/scope/group).
- `BcnesaCompetition` enum drives valid competition folder mapping.
- Player/match import inference mirrors FEDESP adapter:
  - Club by normalized Levenshtein minimum.
  - Practicioner by `NameSimilarity` maximum.
- **Phase 2 transaction model**: Row processing is delegated to `BcnesaRowProcessingTransactionalDelegate` (in `service/delegate/`) annotated with `@Transactional(propagation = REQUIRES_NEW)`. Each row commits or rolls back independently. The outer service loop catches exceptions and continues to the next row.
- **Phase 2 flush ordering**: `EntityManager.flush()` is called explicitly after each new entity INSERT (`ClubMember` → `SeasonPlayer` → `SeasonPlayerResult`) to enforce FK dependency ordering and counteract Hibernate's `order_inserts` reordering within a transaction.
- **Phase 2 cache detachment**: `ImportRunContext` caches hold JPA entities that become detached after each `REQUIRES_NEW` transaction commits. `@ManyToOne` associations (`ClubMember.club`, `SeasonPlayer.clubMember`, `SeasonPlayerResult.seasonPlayer`) must remain EAGER (JPA default). `License` must be `@Embeddable` for detached access to `getLicense().id()`.
- **Phase 2 exception safety**: `getOrCreateClubMember()` and `getOrCreateSeasonPlayer()` no longer swallow exceptions. Save failures propagate through the `REQUIRES_NEW` boundary and are caught by the outer service loop.

## Risks and pitfalls
- Enum-folder mismatch causes files to be skipped implicitly or fail lookup.
- Filename mismatch throws runtime exception while scanning.
- Hardcoded scope values (`provincial`, `bcn`) may need review if source structure changes.
- Inference quality depends on pre-imported clubs/practicioners and naming consistency.
- `ensureClubMemberSeasonRange()` saves ClubMember on every run (not just first run), contributing to `entityManagerFlushCount` on re-runs.

## Safe change rules for agents
1. Keep folder-discovery rules consistent with `BcnesaCompetition` values.
2. Preserve row-index mapping unless schema update is confirmed.
3. If adjusting dedupe keys, ensure reruns remain idempotent.
4. Prefer additive enum changes for new categories over changing existing identifiers.
5. Do not move row-processing logic out of `BcnesaRowProcessingTransactionalDelegate` without re-validating FK flush ordering.

## Typical change workflow
1. Confirm new BCNESA season/folder/file naming format.
2. Update finder + iterator metadata extraction.
3. Update row extractor and importer mapping logic.
4. Validate module build from repo root: `mvn -pl tt-data-league-import-bcnesa-csv-adapter -am test`.

## Gaps to keep in mind
- Phase 2 tests are unit tests (no Spring context). EntityManager is injected via reflection in `BcnesaRowProcessingTransactionalDelegatePhase2Test`.
- The `@Transactional(REQUIRES_NEW)` behavior is only effective at runtime (Spring-proxied bean). Unit tests verify exception propagation and call ordering but not transaction rollback semantics.
- `ensureClubMemberSeasonRange()` always saves on first access per run even if the range already exists in the entity — no pre-check for existing range.


