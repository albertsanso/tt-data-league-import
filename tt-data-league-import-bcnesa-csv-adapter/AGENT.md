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
- BCNESA importer currently wraps row processing in transactional method and error guards per row.

## Risks and pitfalls
- Enum-folder mismatch causes files to be skipped implicitly or fail lookup.
- Filename mismatch throws runtime exception while scanning.
- Hardcoded scope values (`provincial`, `bcn`) may need review if source structure changes.
- Inference quality depends on pre-imported clubs/practicioners and naming consistency.

## Safe change rules for agents
1. Keep folder-discovery rules consistent with `BcnesaCompetition` values.
2. Preserve row-index mapping unless schema update is confirmed.
3. If adjusting dedupe keys, ensure reruns remain idempotent.
4. Prefer additive enum changes for new categories over changing existing identifiers.

## Typical change workflow
1. Confirm new BCNESA season/folder/file naming format.
2. Update finder + iterator metadata extraction.
3. Update row extractor and importer mapping logic.
4. Validate module build from repo root: `mvn -pl tt-data-league-import-bcnesa-csv-adapter -am test`.

## Gaps to keep in mind
- No tests currently under `src/test/java` in this module.
- Some helper methods in import service appear unused; avoid removing unless requested.

