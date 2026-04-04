# tt-data-league-import

Multi-module Maven project (Java 21) that imports table-tennis league data from federation CSV exports into a shared core data model and PostgreSQL database.

Use this README for quick setup and daily usage. For deep architecture and change-routing rules, see [`AGENTS.md`](AGENTS.md).

## Project badges
CI and release badges are not configured yet in this repository. After adding a pipeline, place badges at the top of this file using links similar to:

```markdown
![Build](https://img.shields.io/github/actions/workflow/status/<org>/<repo>/ci.yml?branch=main)
![Tests](https://img.shields.io/badge/tests-not%20configured-lightgrey)
![Java](https://img.shields.io/badge/java-21-blue)
```

## Prerequisites
- Java 21
- Maven 3.9+
- PostgreSQL (default runtime fallback points to `localhost:5432/ttleaguedata`)

## Quick start
```bash
mvn clean install
```

Run the runtime module:

```bash
mvn -pl tt-data-league-import-runtime spring-boot:run
```

The application expects command-line arguments for federation/workflow/base-folder (see examples below).

If needed, override DB connection via environment variables before running:

```powershell
$env:DB_TTLEAGUEDATA_JDBC_URL = "jdbc:postgresql://localhost:5432/ttleaguedata"
$env:DB_TTLEAGUEDATA_CREDENTIAL_USERNAME = "ttleagueuser"
$env:DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD = "ttleaguepass"
```

## Run from JAR

After building, you can run the fat JAR directly. Previously you need to create and .env file with DB credentials if you want to override defaults:
```
# touch .env
# .env content:
DB_TTLEAGUEDATA_JDBC_URL=jdbc:postgresql://localhost:5432/ttleaguedata
DB_TTLEAGUEDATA_CREDENTIAL_USERNAME=ttleagueuser
DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD=ttleaguepass
```

```bash
java -jar tt-data-league-import-runtime/target/tt-data-league-import-runtime-0.0.1-SNAPSHOT.jar --federation=fedesp --workflow=clubs --base-folder=C:/data/fedesp
```

## Deploy and run in WSL Debian
Use the provided scripts to build a self-contained JAR and deploy it to a local WSL Debian instance in one step.
Scripts are located under `scripts/wsl-local-deployment`, and commands below assume your current directory is the repository root.

**Step 1 – Deploy from PowerShell (Windows host):**
```powershell
.\scripts\wsl-local-deployment\deploy-to-wsl.ps1
# With options:
.\scripts\wsl-local-deployment\deploy-to-wsl.ps1 -WslDistro Debian -DeployDirLinux ~/tt-data-league-importer
```

The script:
1. Runs `mvn clean package` to produce an executable fat JAR.
2. Copies the JAR and `run-importer.sh` to the WSL deploy directory.
3. Fixes line endings and marks the runner executable.

**Step 2 – Run from inside WSL:**
```bash
~/tt-data-league-importer/run-importer.sh --federation=fedesp --workflow=clubs --base-folder=/data/fedesp
~/tt-data-league-importer/run-importer.sh --federation=bcnesa --workflow=results --base-folder=/data/bcnesa --season=2024-2025
```

**Optional – DB credentials via `.env` in WSL:**
```bash
# ~/tt-data-league-importer/.env
DB_TTLEAGUEDATA_JDBC_URL=jdbc:postgresql://localhost:5432/ttleaguedata
DB_TTLEAGUEDATA_CREDENTIAL_USERNAME=myuser
DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD=mypass
```

## Run import workflows
Accepted runtime arguments are implemented in [`tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java`](tt-data-league-import-runtime/src/main/java/org/cttelsamicsterrassa/data/importer/runtime/ImporterApplication.java):
- `--federation=<fedesp|bcnesa>`
- `--workflow=<clubs|practicioners|results>`
- `--base-folder=<path>`
- `--season=<YYYY-YYYY>` (optional)

Example runs:

```bash
mvn -pl tt-data-league-import-runtime spring-boot:run -Dspring-boot.run.arguments="--federation=fedesp --workflow=clubs --base-folder=C:/data/fedesp"
mvn -pl tt-data-league-import-runtime spring-boot:run -Dspring-boot.run.arguments="--federation=bcnesa --workflow=results --base-folder=C:/data/bcnesa --season=2024-2025"
```

## Supported input formats
| Federation | Base discovery contract | File naming contract | Notes |
|---|---|---|---|
| `fedesp` | Season folders under base path with `YYYY-YYYY` | `rfetm-{season}-(gender)-(category)-group-(group)-teamid-(id)_matches.csv` | Uses fixed CSV column indexes; mismatched filenames can fail scan |
| `bcnesa` | `{base}/{season}/{competitionType}/{competitionLevelFolder}/` | `jornada{N}-g{G}.csv` | Season must pass `YYYY-YYYY`; competition mapping uses `BcnesaCompetition` |

Detailed adapter contracts are documented in [`tt-data-league-import-fedesp-csv-adapter/AGENTS.md`](tt-data-league-import-fedesp-csv-adapter/AGENTS.md) and [`tt-data-league-import-bcnesa-csv-adapter/AGENTS.md`](tt-data-league-import-bcnesa-csv-adapter/AGENTS.md).

## Configuration
Runtime datasource is configured through environment-backed placeholders in [`tt-data-league-import-runtime/src/main/resources/application.yml`](tt-data-league-import-runtime/src/main/resources/application.yml):

```yaml
spring:
  datasource:
	url: ${DB_TTLEAGUEDATA_JDBC_URL:jdbc:postgresql://localhost:5432/ttleaguedata}
	username: ${DB_TTLEAGUEDATA_CREDENTIAL_USERNAME:ttleagueuser}
	password: ${DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD:ttleaguepass}
```

Set `DB_TTLEAGUEDATA_JDBC_URL`, `DB_TTLEAGUEDATA_CREDENTIAL_USERNAME`, and `DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD` for your environment.

## Documentation map
- Repository architecture and operating rules: [`AGENTS.md`](AGENTS.md)
- Runtime module guide: [`tt-data-league-import-runtime/AGENTS.md`](tt-data-league-import-runtime/AGENTS.md)
- Shared module guide: [`tt-data-league-import-shared/AGENTS.md`](tt-data-league-import-shared/AGENTS.md)
- FEDESP adapter guide: [`tt-data-league-import-fedesp-csv-adapter/AGENTS.md`](tt-data-league-import-fedesp-csv-adapter/AGENTS.md)
- BCNESA adapter guide: [`tt-data-league-import-bcnesa-csv-adapter/AGENTS.md`](tt-data-league-import-bcnesa-csv-adapter/AGENTS.md)

## Change routing
| Module | Change here if... | Avoid changing here for... |
|---|---|---|
| `tt-data-league-import-runtime` | startup order, Spring wiring, datasource/config, import invocation flow | federation CSV parsing rules, shared matching logic |
| `tt-data-league-import-shared` | reusable iterator/import behavior, common records/contracts, shared name matching | FEDESP/BCNESA naming conventions and row indexes |
| `tt-data-league-import-fedesp-csv-adapter` | FEDESP filename conventions, FEDESP columns, FEDESP-specific mapping | BCNESA folder taxonomy or runtime orchestration |
| `tt-data-league-import-bcnesa-csv-adapter` | BCNESA season/folder discovery, BCNESA competition taxonomy, BCNESA-specific mapping | FEDESP filename rules or runtime orchestration |

## Project structure
- Root parent build: [`pom.xml`](pom.xml)
- WSL deployment scripts: [`scripts/wsl-local-deployment`](scripts/wsl-local-deployment)
- Runtime module: [`tt-data-league-import-runtime`](tt-data-league-import-runtime)
- Shared module: [`tt-data-league-import-shared`](tt-data-league-import-shared)
- FEDESP adapter module: [`tt-data-league-import-fedesp-csv-adapter`](tt-data-league-import-fedesp-csv-adapter)
- BCNESA adapter module: [`tt-data-league-import-bcnesa-csv-adapter`](tt-data-league-import-bcnesa-csv-adapter)

## Current limitations
- No automated tests are currently present under module `src/test/java` directories.
- Import adapters rely on stable CSV input conventions.
- Name matching is heuristic and may need manual validation for ambiguous cases.

## Troubleshooting
- **Application exits with usage text**: verify `--federation`, `--workflow`, and `--base-folder` are all provided.
- **Unknown federation/workflow**: valid values are `fedesp|bcnesa` and `clubs|practicioners|results`.
- **Database connection failure**: check PostgreSQL availability and credentials in [`tt-data-league-import-runtime/src/main/resources/application.yml`](tt-data-league-import-runtime/src/main/resources/application.yml).
- **No files processed**: verify season/folder/file names follow the contracts in `Supported input formats`.
- **Unexpected mapping results**: run clubs/practicioners imports before results and validate ambiguous names manually.

## Contributing
- Prefer small, module-local changes.
- Follow routing and architecture rules in [`AGENTS.md`](AGENTS.md).
- Keep module-specific implementation details inside each module `AGENTS.md`.

## License
See [`LICENSE`](LICENSE).

## Maintenance
- Keep this file focused on quick navigation and module routing.
- Keep architecture details, constraints, and deep implementation guidance in [`AGENTS.md`](AGENTS.md) and module `AGENTS.md` files.
