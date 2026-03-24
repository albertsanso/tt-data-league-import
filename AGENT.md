# AGENT Guide - tt-data-league-import

## Repository purpose
- Multi-module Maven project for importing table-tennis league data into the core data model and persistence layer.
- Aggregates federation-specific CSV adapters, shared import utilities, and a Spring Boot runtime used to execute import workflows.
- Primary responsibility of this repository: transform external season/match CSV exports into persisted club, practicioner, season-player, and single-match data.

## Module architecture
This repository is organized as a parent `pom` with four child modules:

1. `tt-data-league-import-runtime`
   - Spring Boot runtime and orchestration entrypoint.
   - Owns startup flow, DB configuration, and service invocation.
   - Module guide: [`tt-data-league-import-runtime/AGENT.md`](tt-data-league-import-runtime/AGENT.md)

2. `tt-data-league-import-shared`
   - Reusable iterator/import scaffolding and name-similarity utilities.
   - Serves as the adapter-neutral contract layer.
   - Module guide: [`tt-data-league-import-shared/AGENT.md`](tt-data-league-import-shared/AGENT.md)

3. `tt-data-league-import-fedesp-csv-adapter`
   - FEDESP / RFETM CSV adapter.
   - Owns FEDESP-specific filename parsing, row extraction, and mapping rules.
   - Module guide: [`tt-data-league-import-fedesp-csv-adapter/AGENT.md`](tt-data-league-import-fedesp-csv-adapter/AGENT.md)

4. `tt-data-league-import-bcnesa-csv-adapter`
   - BCNESA CSV adapter.
   - Owns BCNESA-specific folder structure, competition taxonomy, and row mapping rules.
   - Module guide: [`tt-data-league-import-bcnesa-csv-adapter/AGENT.md`](tt-data-league-import-bcnesa-csv-adapter/AGENT.md)

## Dependency flow
Use this mental model before making changes:

- `runtime` depends on adapter modules and executes import workflows.
- adapter modules depend on `shared` plus external core domain/repository modules.
- `shared` contains reusable import infrastructure and fuzzy matching logic.
- Root `pom.xml` only aggregates modules and centralizes dependency management.

In short:

`runtime -> adapters -> shared -> external core domain/repository dependencies`

## Project-wide routing rules
When deciding where to change code, use these rules first:

- Change `tt-data-league-import-runtime` for:
  - startup flow
  - execution sequencing
  - base-folder/config wiring
  - Spring Boot / datasource concerns
- Change `tt-data-league-import-shared` for:
  - reusable iterator behavior
  - common records/contracts
  - name grouping / similarity logic used by multiple adapters
- Change `tt-data-league-import-fedesp-csv-adapter` for:
  - FEDESP filename conventions
  - FEDESP row column indexes
  - FEDESP-specific import mapping
- Change `tt-data-league-import-bcnesa-csv-adapter` for:
  - BCNESA season/folder/file discovery
  - BCNESA competition enum/taxonomy
  - BCNESA-specific row extraction and persistence mapping

## Cross-module workflow
Typical end-to-end import flow is:

1. Runtime starts Spring Boot and loads repositories/beans.
2. Runtime invokes one or more federation adapter services.
3. Adapter iterates over files using its federation-specific iterator.
4. Shared base classes collect row infos and provide common utilities.
5. Adapter maps CSV rows into core domain entities and persists them through repositories.

## Repository-wide risks and constraints
These apply across modules and should be considered by any agent working here:

- The runtime currently contains machine-specific Windows filesystem paths in `ImporterApplication`.
- Both adapters rely heavily on fixed CSV schemas and naming conventions.
- Shared import flow materializes rows in memory before processing.
- Name/club inference is heuristic and can produce incorrect matches in ambiguous cases.
- There are no tests currently present under the module `src/test/java` folders.
- External dependencies on core domain/repository modules are contractual and should be treated as stable integration boundaries.

## Safe change rules for agents
1. Prefer the smallest module-local change that solves the problem.
2. Do not move federation-specific parsing rules into `shared` unless both adapters truly need them.
3. Do not embed parsing or mapping logic into `runtime`.
4. Preserve idempotent import behavior wherever repositories are used for deduplication.
5. If changing row extraction, filename parsing, or fuzzy matching, document the input contract change in the affected module guide.
6. Avoid broad renames of project-specific spellings like `Practicioner` or `Groupping` unless the change is explicitly requested and coordinated across modules.

## Working conventions for future AGENT docs
- Update this root `AGENT.md` when repository structure, module boundaries, or dependency flow changes.
- Update `AGENT_INDEX.md` when links, routing tables, or navigation shortcuts need refresh.
- Update module `AGENT.md` files when implementation details, risks, owned files, or workflows change within a module.

## Recommended entrypoints for investigation
Start here depending on the task:

- Repo structure and modules: [`pom.xml`](pom.xml)
- Runtime orchestration: [`tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java`](tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java)
- Shared import base classes: [`tt-data-league-import-shared/AGENT.md`](tt-data-league-import-shared/AGENT.md)
- FEDESP adapter details: [`tt-data-league-import-fedesp-csv-adapter/AGENT.md`](tt-data-league-import-fedesp-csv-adapter/AGENT.md)
- BCNESA adapter details: [`tt-data-league-import-bcnesa-csv-adapter/AGENT.md`](tt-data-league-import-bcnesa-csv-adapter/AGENT.md)

## Quick navigation
For a concise lookup table of modules, responsibilities, and "change here if..." guidance, see [`AGENT_INDEX.md`](AGENT_INDEX.md).

