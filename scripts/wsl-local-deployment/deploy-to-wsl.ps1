# =============================================================================
# deploy-to-wsl.ps1 - Build executable JAR and deploy to WSL Debian
# =============================================================================
#
# Prerequisites:
#   - Maven 3.9+ on PATH
#   - Java 21 on PATH
#   - WSL with a Debian-based distro running
#
# Usage:
#   .\deploy-to-wsl.ps1
#   .\deploy-to-wsl.ps1 -WslDistro Ubuntu -DeployDirLinux ~/my-dir
#
# Parameters:
#   -WslDistro       WSL distribution name (default: Debian)
#   -DeployDirLinux  Target deploy directory inside WSL (default: ~/tt-data-league-importer)
# =============================================================================
[CmdletBinding()]
param(
    [string]$WslDistro      = "Debian",
    [string]$DeployDirLinux = "~/tt-data-league-importer"
)

$ErrorActionPreference = "Stop"
$ScriptDir     = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot      = (Resolve-Path (Join-Path $ScriptDir "..\..")).Path
$RuntimeModule = "tt-data-league-import-runtime"

# ── Helper ────────────────────────────────────────────────────────────────────
function Write-Step([string]$msg) {
    Write-Host "`n$msg" -ForegroundColor Cyan
}
function Write-Ok([string]$msg) {
    Write-Host "  $msg" -ForegroundColor Green
}
function Write-Err([string]$msg) {
    Write-Host "  ERROR: $msg" -ForegroundColor Red
}

# Validate expected repository anchors from the resolved root.
$RequiredPaths = @(
    (Join-Path $RepoRoot "pom.xml"),
    (Join-Path $RepoRoot $RuntimeModule),
    (Join-Path $RepoRoot "run-importer.sh")
)
foreach ($RequiredPath in $RequiredPaths) {
    if (-not (Test-Path $RequiredPath)) {
        Write-Err "Repository root appears invalid ('$RepoRoot'). Missing required path: $RequiredPath"
        exit 1
    }
}

# ── 1. Build ──────────────────────────────────────────────────────────────────
Write-Step "[1/3] Building executable JAR..."
Push-Location $RepoRoot
try {
    & mvn clean package `
        --batch-mode `
        -pl $RuntimeModule `
        -am `
        -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed (exit $LASTEXITCODE)" }
} finally {
    Pop-Location
}

# Locate the Spring Boot repackaged JAR (exclude the original thin .jar.original)
$JarFile = Get-ChildItem "$RepoRoot\$RuntimeModule\target\*.jar" |
    Where-Object { $_.Name -notlike "*.jar.original" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if (-not $JarFile) {
    Write-Err "No JAR found under $RepoRoot\$RuntimeModule\target\"
    exit 1
}
Write-Ok "JAR built: $($JarFile.Name)"

# ── 2. Prepare WSL deploy directory ──────────────────────────────────────────
Write-Step "[2/3] Preparing deploy directory in WSL ($WslDistro)..."

# Expand ~ and resolve the absolute Linux path
$DeployDirResolved = (wsl -d $WslDistro -- bash -c "mkdir -p $DeployDirLinux && realpath $DeployDirLinux").Trim()
if ($LASTEXITCODE -ne 0) {
    Write-Err "Failed to create or resolve deploy dir '$DeployDirLinux' in WSL distro '$WslDistro'"
    exit 1
}
Write-Ok "Deploy path: $DeployDirResolved"

# Convert Linux path to Windows UNC path (\\wsl$\<Distro>\...)
$WslWinDir = "\\wsl`$\$WslDistro" + ($DeployDirResolved -replace '/', '\')

if (-not (Test-Path $WslWinDir)) {
    Write-Err "UNC path not accessible: $WslWinDir"
    exit 1
}

# ── 3. Copy files ─────────────────────────────────────────────────────────────
Write-Step "[3/3] Copying files to WSL..."

# Copy JAR
Copy-Item -Path $JarFile.FullName -Destination $WslWinDir -Force
Write-Ok "Copied: $($JarFile.Name)"

# Copy runner script
$RunnerScript = Join-Path $RepoRoot "run-importer.sh"
if (-not (Test-Path $RunnerScript)) {
    Write-Err "run-importer.sh not found at repo root ($RepoRoot)"
    exit 1
}
Copy-Item -Path $RunnerScript -Destination $WslWinDir -Force
Write-Ok "Copied: run-importer.sh"

# Fix line endings (CRLF -> LF) and make executable
wsl -d $WslDistro -- bash -c "sed -i 's/\r//' $DeployDirLinux/run-importer.sh && chmod +x $DeployDirLinux/run-importer.sh"
Write-Ok "run-importer.sh: line endings fixed, marked executable"

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== Deployment complete ===" -ForegroundColor Green
Write-Host ""
Write-Host "  Distro  : $WslDistro"
Write-Host "  Location: $DeployDirResolved"
Write-Host ""
Write-Host "  Open WSL and run:" -ForegroundColor Yellow
Write-Host ""
Write-Host "    # Import clubs (all seasons)" -ForegroundColor DarkGray
Write-Host "    $DeployDirLinux/run-importer.sh --federation=fedesp --workflow=clubs --base-folder=/data/fedesp" -ForegroundColor White
Write-Host ""
Write-Host "    # Import match results for a specific season" -ForegroundColor DarkGray
Write-Host "    $DeployDirLinux/run-importer.sh --federation=bcnesa --workflow=results --base-folder=/data/bcnesa --season=2024-2025" -ForegroundColor White
Write-Host ""
Write-Host "  Optional: create $DeployDirLinux/.env to set DB credentials:" -ForegroundColor Yellow
Write-Host "    DB_TTLEAGUEDATA_JDBC_URL=jdbc:postgresql://host:5432/ttleaguedata" -ForegroundColor DarkGray
Write-Host "    DB_TTLEAGUEDATA_CREDENTIAL_USERNAME=myuser" -ForegroundColor DarkGray
Write-Host "    DB_TTLEAGUEDATA_CREDENTIAL_PASSWORD=mypass" -ForegroundColor DarkGray
Write-Host ""

