# Phase 1 Benchmark Runbook (BCNESA Results Import)

This runbook captures a repeatable before/after protocol for validating Phase 1 changes in `BcnesaPlayerAndResultsInitialImportService`.

## Objective
- Measure runtime and DB-query reduction from Phase 1 cache + conditional-save changes.
- Verify idempotency remains intact across reruns.
- Collect enough evidence to compare baseline and optimized runs with the same dataset and DB state.

## Preconditions
- Same importer code path and same BCNESA season dataset for all runs.
- Same DB snapshot for baseline and Phase 1 runs.
- Runtime settings unchanged between runs (JVM, DB host, machine load as stable as possible).
- Log output includes `BCNESA Phase1 metrics:` lines (already added in service).

## Folder layout for artifacts
Create a local benchmark output folder (outside git if preferred):

- `benchmark-output/baseline-run1.log`
- `benchmark-output/baseline-run2.log`
- `benchmark-output/phase1-run1.log`
- `benchmark-output/phase1-run2.log`
- `benchmark-output/metrics-summary.txt`

## Step-by-step protocol

### 1) Baseline capture (pre-Phase1 revision)
1. Checkout the baseline revision (before Phase 1 changes).
2. Restore DB snapshot.
3. Run importer once and capture log as `baseline-run1.log`.
4. Run importer again immediately and capture `baseline-run2.log` (idempotency pass).

### 2) Phase 1 capture (current revision)
1. Checkout Phase 1 revision.
2. Restore the same DB snapshot used in baseline.
3. Run importer once and capture `phase1-run1.log`.
4. Run importer again immediately and capture `phase1-run2.log`.

### 3) Compare key outcomes
- Runtime per run.
- `rowsTotal`, `rowsProcessed`, `rowsSkippedInferenceMiss`, `rowExceptions`.
- Cache hits/misses (`clubMember`, `seasonPlayer`, `seasonPlayerResult`, `singleMatch`).
- Save counts (`clubMemberSaved`, `seasonPlayerSaved`, `seasonPlayerResultSaved`, `playersSingleMatchSaved`, `saveSkippedNoChange`).
- Rerun idempotency signal: writes should be minimal/near zero on run2.

## Command templates (PowerShell)
Adjust these to your normal runtime entrypoint and local DB setup.

```powershell
Set-Location "C:\git\tt-data-league-import"

# Example: run importer and capture full logs
mvn -pl tt-data-league-import-runtime spring-boot:run *> .\benchmark-output\phase1-run1.log
mvn -pl tt-data-league-import-runtime spring-boot:run *> .\benchmark-output\phase1-run2.log
```

Extract only Phase 1 metric lines from one log:

```powershell
Select-String -Path .\benchmark-output\phase1-run1.log -Pattern "BCNESA Phase1" | ForEach-Object { $_.Line }
```

Or use the helper script added in this iteration:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\performance\extract-phase1-metrics.ps1 -LogPath .\benchmark-output\phase1-run1.log
```

## Acceptance criteria
- Functional row coverage is not lower than baseline.
- Error/miss rates do not regress.
- DB read/write behavior improves (or at least no worse) per processed row.
- Second run is effectively idempotent (few or zero new writes).

## Reporting template
Use this compact table for the final report:

| Metric | Baseline Run1 | Baseline Run2 | Phase1 Run1 | Phase1 Run2 |
|---|---:|---:|---:|---:|
| Total runtime (ms) |  |  |  |  |
| rowsProcessed |  |  |  |  |
| rowExceptions |  |  |  |  |
| clubMemberSaved |  |  |  |  |
| seasonPlayerSaved |  |  |  |  |
| seasonPlayerResultSaved |  |  |  |  |
| playersSingleMatchSaved |  |  |  |  |
| saveSkippedNoChange |  |  |  |  |
| clubMember cache hit/miss |  |  |  |  |
| seasonPlayer cache hit/miss |  |  |  |  |
| seasonPlayerResult cache hit/miss |  |  |  |  |
| singleMatch cache hit/miss |  |  |  |  |

