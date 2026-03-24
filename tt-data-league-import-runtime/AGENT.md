# AGENT Guide - tt-data-league-import-runtime

## Module purpose
- Spring Boot runtime and orchestration module.
- Boots the application, wires scanning/repositories/entities, and invokes adapter import services.
- Owns infrastructure-level concerns (data source, runtime config), not CSV parsing logic.

## Build and dependency position
- Maven artifact: `tt-data-league-import-runtime`.
- Depends on:
  - `spring-boot-starter-data-jpa`
  - `spring-boot-starter-jdbc`
  - `org.postgresql:postgresql`
  - `tt-data-league-import-bcnesa-csv-adapter`
  - `tt-data-league-import-fedesp-csv-adapter`
- This module is the executable entrypoint for end-to-end import runs.

## Owned code and responsibilities
- `src/main/java/.../runtime/ImporterApplication.java`
  - Main class and `CommandLineRunner` orchestration point.
  - Calls adapter services for club/practicioner/match imports.
  - Contains currently hardcoded local base-folder paths and commented execution switches.
- `src/main/java/.../runtime/DataSourceConfig.java`
  - Builds `HikariDataSource` from Spring `DataSourceProperties`.
- `src/main/resources/application.properties`
  - DB, JPA, Hikari, and actuator settings.

## Agent boundaries
- Prefer changing this module when task is about:
  - Runtime flow order
  - Which imports run and when
  - Configurability of base paths
  - DataSource/Boot setup
- Do not implement parser/domain mapping logic here; change adapter/shared modules instead.

## Key behavior notes
- `@SpringBootApplication(scanBasePackages = "org.cttelsamicsterrassa")` enables broad scan; bean naming collisions are possible if services overlap.
- `ImporterApplication.run(...)` currently defaults to no active import call (mostly commented lines).
- Base input paths are machine-specific Windows paths.

## Risks and pitfalls
- Hardcoded paths reduce portability and CI friendliness.
- Comment-driven execution selection can drift and is error-prone.
- Wide package scan can hide accidental bean loading.

## Safe change rules for agents
1. Keep runtime orchestrator thin; avoid embedding parsing/business logic.
2. Prefer property-driven config over hardcoded paths.
3. If enabling new run modes, preserve existing behavior behind explicit flags.
4. Keep startup idempotent where possible (imports rely on repository dedupe in adapters).

## Typical change workflow
1. Add/update config keys in `application.properties`.
2. Inject them in `ImporterApplication` (or dedicated config bean).
3. Wire adapter service call sequence.
4. Run module build from repo root: `mvn -pl tt-data-league-import-runtime -am test`.

## Gaps to keep in mind
- No tests currently under `src/test/java` in this module.
- Runtime currently assumes external data directories already exist with expected structure.

