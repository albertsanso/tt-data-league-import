# AGENT Guide - tt-data-league-import-fedesp-csv-adapter

## Module purpose
- Adapter for RFETM/FEDESP CSV match exports.
- Discovers season folders, parses match CSV rows, and maps them into core domain entities.
- Handles FEDESP-specific filename conventions and column indexes.

## Build and dependency position
- Maven artifact: `tt-data-league-import-fedesp-csv-adapter`.
- Depends on:
  - `tt-data-league-core-domain`
  - `tt-data-league-core-repository-jpa`
  - `tt-data-league-import-shared`
- Loaded by runtime module for executable imports.

## Owned code and responsibilities
- File-system discovery and iterator:
  - `shared/service/FedespCsvRepositoryFinderService.java`
  - `shared/service/FedespMatchResultDetailsByLineIterator.java`
- Row extraction and mapping:
  - `shared/service/FedespCsvFileRowInfoExtractor.java`
  - `shared/model/fs/*`
- Import services:
  - `club/service/FedespClubInitialImportService.java`
  - `club/service/FedespPracticionerInitialImportService.java`
  - `player_single_match/service/FedespPlayerAndResultsImportService.java`

## Input contract (important)
- Season folder expected under base folder with `YYYY-YYYY` naming.
- File name regex expected by iterator:
  - `rfetm-{season}-(gender)-(category)-group-(group)-teamid-(id)_matches.csv`
- Parser assumes fixed column positions for teams, players, licenses, and scores.

## Agent boundaries
- Change this module for FEDESP format changes only.
- Keep shared/generalized logic in `tt-data-league-import-shared`.
- Keep runtime execution policy in `tt-data-league-import-runtime`.

## Key behavior notes
- File name mismatches currently throw runtime exceptions during folder scan.
- Date parsing in extractor falls back to `ZonedDateTime.now()` on parse error after stack trace.
- Player/match import uses fuzzy inference:
  - Club inferred by minimum Levenshtein distance on normalized names.
  - Practicioner inferred by max `NameSimilarity` score.
- Dedup relies on repository lookup + generated `uniqueRowId`.

## Risks and pitfalls
- Column index drift in source CSV will silently corrupt mappings.
- Fallback `now()` date can hide bad source date formats.
- Inference may link to wrong club/practicioner if names are very similar.
- Service methods process all rows in-memory (`fetchCsvRowInfos()` usage).

## Safe change rules for agents
1. Preserve row index constants behavior unless source schema has changed and is documented.
2. Keep filename regex and metadata extraction aligned.
3. When altering inference logic, validate against known ambiguous names.
4. Maintain idempotency assumptions (`find...` then `save`) for reruns.

## Typical change workflow
1. Confirm new FEDESP export naming/column schema.
2. Update iterator regex and/or row extractor indices.
3. Update import service mapping and dedupe key only if required.
4. Validate module build from repo root: `mvn -pl tt-data-league-import-fedesp-csv-adapter -am test`.

## Gaps to keep in mind
- No tests currently under `src/test/java` in this module.
- Several methods include exploratory or unused code paths; avoid broad cleanup unless requested.

