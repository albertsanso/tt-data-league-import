# AGENT Index - tt-data-league-import

This file is the fast navigation entrypoint for agents working in this repository.
For repository-wide architecture, boundaries, and operating rules, see [`AGENT.md`](AGENT.md).

## Module quick links
- Root guide: [`AGENT.md`](AGENT.md)
- Runtime: [`tt-data-league-import-runtime/AGENT.md`](tt-data-league-import-runtime/AGENT.md)
- Shared: [`tt-data-league-import-shared/AGENT.md`](tt-data-league-import-shared/AGENT.md)
- FEDESP adapter: [`tt-data-league-import-fedesp-csv-adapter/AGENT.md`](tt-data-league-import-fedesp-csv-adapter/AGENT.md)
- BCNESA adapter: [`tt-data-league-import-bcnesa-csv-adapter/AGENT.md`](tt-data-league-import-bcnesa-csv-adapter/AGENT.md)

## Responsibility matrix
| Module | Main role | Change here if... | Avoid changing here for... |
|---|---|---|---|
| `tt-data-league-import-runtime` | Spring Boot startup and orchestration | you need to change run order, configuration, datasource wiring, or import invocation | CSV schema parsing or reusable matching logic |
| `tt-data-league-import-shared` | Common iterator/import scaffolding and fuzzy matching | logic should be reused by both adapters | federation-specific file naming or column positions |
| `tt-data-league-import-fedesp-csv-adapter` | FEDESP CSV discovery, extraction, and mapping | FEDESP filenames, row indexes, FEDESP import rules changed | BCNESA folder rules or shared abstractions |
| `tt-data-league-import-bcnesa-csv-adapter` | BCNESA folder discovery, extraction, and mapping | BCNESA folder/file taxonomy, enums, or row indexes changed | FEDESP filename rules or runtime startup policy |

## Dependency and call flow
- Root `pom.xml` aggregates modules and manages common dependency versions.
- Runtime loads Spring beans from all modules and invokes adapter services.
- Adapters use shared iterator/base classes and persist via external core repositories.

Flow summary:

`runtime -> fedesp/bcnesa adapters -> shared utilities -> external domain/repository modules`

## Key files by area
### Root
- [`pom.xml`](pom.xml) - parent module definition and dependency management
- [`README.md`](README.md) - minimal project readme
- [`AGENT.md`](AGENT.md) - repository operating guide
- [`AGENT_INDEX.md`](AGENT_INDEX.md) - this navigation file

### Runtime
- [`tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java`](tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java)
- [`tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/DataSourceConfig.java`](tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/DataSourceConfig.java)
- [`tt-data-league-import-runtime/src/main/resources/application.properties`](tt-data-league-import-runtime/src/main/resources/application.properties)

### Shared
- [`tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/MatchResultDetailsByLineIterator.java`](tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/MatchResultDetailsByLineIterator.java)
- [`tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/LineByLineInitialImportService.java`](tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/LineByLineInitialImportService.java)
- [`tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/name/NameSimilarity.java`](tt-data-league-import-shared/src/main/java/org/cttelsamicsterrassa/data/importer/shared/service/name/NameSimilarity.java)

### FEDESP adapter
- [`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/shared/service/FedespMatchResultDetailsByLineIterator.java`](tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/shared/service/FedespMatchResultDetailsByLineIterator.java)
- [`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/shared/service/FedespCsvFileRowInfoExtractor.java`](tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/shared/service/FedespCsvFileRowInfoExtractor.java)
- [`tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/player_single_match/service/FedespPlayerAndResultsImportService.java`](tt-data-league-import-fedesp-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/fedesp/player_single_match/service/FedespPlayerAndResultsImportService.java)

### BCNESA adapter
- [`tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/shared/service/BcnesaMatchResultDetailsByLineIterator.java`](tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/shared/service/BcnesaMatchResultDetailsByLineIterator.java)
- [`tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/shared/service/BcnesaCsvRepositoryFinderService.java`](tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/shared/service/BcnesaCsvRepositoryFinderService.java)
- [`tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/service/BcnesaPlayerAndResultsInitialImportService.java`](tt-data-league-import-bcnesa-csv-adapter/src/main/java/org/cttelsamicsterrassa/data/importer/csv_adapter/bcnesa/player_single_match/service/BcnesaPlayerAndResultsInitialImportService.java)

## Change routing cheatsheet
- "I need to make imports configurable" -> Runtime
- "Both adapters need a new shared helper" -> Shared
- "FEDESP file names or columns changed" -> FEDESP adapter
- "BCNESA season folders or competition folders changed" -> BCNESA adapter
- "I need to understand the whole repo first" -> Root `AGENT.md`, then the target module guide

## Current known project-wide caveats
- No test sources were found in the module `src/test/java` trees.
- Runtime orchestration currently includes hardcoded Windows data paths in `ImporterApplication`.
- Adapter imports depend on stable input contracts and heuristics for matching clubs/practicioners.

## Maintenance rule
- Update this file when module links, routing advice, or key entrypoints change.
- Update module-level `AGENT.md` files when implementation details change inside a specific module.

