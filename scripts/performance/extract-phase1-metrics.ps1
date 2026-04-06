param(
    [Parameter(Mandatory = $true)]
    [string]$LogPath
)

if (!(Test-Path -Path $LogPath)) {
    Write-Error "Log file not found: $LogPath"
    exit 1
}

$patterns = @(
    "BCNESA Phase1 metrics:",
    "BCNESA Phase1 lookup:",
    "BCNESA Phase1 cache:",
    "BCNESA Phase1 saves:",
    "BCNESA Phase1 timingMs:",
    "BCNESA Phase1 cacheSize:"
)

$lines = Get-Content -Path $LogPath
$matches = @()

foreach ($line in $lines) {
    foreach ($pattern in $patterns) {
        if ($line -like "*$pattern*") {
            $matches += $line
            break
        }
    }
}

if ($matches.Count -eq 0) {
    Write-Host "No Phase 1 metric lines found in $LogPath"
    exit 0
}

Write-Host "Phase 1 metric lines from $LogPath"
Write-Host "--------------------------------------"
$matches | ForEach-Object { Write-Host $_ }

