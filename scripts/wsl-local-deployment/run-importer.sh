#!/usr/bin/env bash
# =============================================================================
# run-importer.sh – Execute tt-data-league-importer inside WSL Debian
# =============================================================================
#
# Usage:
#   ./run-importer.sh --federation=<fedesp|bcnesa> \
#                     --workflow=<clubs|practicioners|results> \
#                     --base-folder=<path> \
#                     [--season=<YYYY-YYYY>]
#
# Environment variables (override datasource defaults):
#   DB_TTLEAGUEDATA_JDBC_URL
#   DB_TTLEAGUEDATA_CREDENTIAL_USERNAME
#   DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD
#
# Optional: place a .env file next to this script to set variables.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Load .env if present ──────────────────────────────────────────────────────
if [[ -f "$SCRIPT_DIR/.env" ]]; then
  echo "[run-importer] Loading environment from $SCRIPT_DIR/.env"
  set -o allexport
  # shellcheck source=/dev/null
  source "$SCRIPT_DIR/.env"
  set +o allexport
fi

# ── Datasource defaults ───────────────────────────────────────────────────────
: "${DB_TTLEAGUEDATA_JDBC_URL:=jdbc:postgresql://localhost:5432/ttleaguedata}"
: "${DB_TTLEAGUEDATA_CREDENTIAL_USERNAME:=ttleagueuser}"
: "${DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD:=ttleaguepass}"

export DB_TTLEAGUEDATA_JDBC_URL
export DB_TTLEAGUEDATA_CREDENTIAL_USERNAME
export DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD

# ── Locate JAR ────────────────────────────────────────────────────────────────
JAR=$(find "$SCRIPT_DIR" -maxdepth 1 \
        -name "tt-data-league-import-runtime-*.jar" \
        ! -name "*.jar.original" \
        2>/dev/null | sort | tail -1)

if [[ -z "$JAR" ]]; then
  echo "[run-importer] ERROR: No tt-data-league-import-runtime-*.jar found in $SCRIPT_DIR" >&2
  echo "[run-importer] Run deploy-to-wsl.ps1 first to build and copy the JAR." >&2
  exit 1
fi

echo "[run-importer] JAR     : $(basename "$JAR")"
echo "[run-importer] DB URL  : $DB_TTLEAGUEDATA_JDBC_URL"
echo "[run-importer] Args    : $*"
echo ""

# ── Execute ───────────────────────────────────────────────────────────────────
exec java -jar "$JAR" "$@"

