param(
    [string]$Phase1Run1Log,
    [string]$Phase1Run2Log,
    [string]$BaselineRun1Log,
    [string]$BaselineRun2Log,
    [string]$OutputPath
)

function Get-MetricLineValue {
    param(
        [string]$LogPath,
        [string]$Prefix,
        [string]$MetricName
    )

    if ([string]::IsNullOrWhiteSpace($LogPath) -or !(Test-Path -Path $LogPath)) {
        return "n/a"
    }

    $escapedPrefix = [regex]::Escape($Prefix)
    $line = Select-String -Path $LogPath -Pattern $escapedPrefix | Select-Object -Last 1
    if ($null -eq $line) {
        return "n/a"
    }

    $content = $line.Line
    $pairPattern = "{0}=([^,]+)" -f [regex]::Escape($MetricName)
    $match = [regex]::Match($content, $pairPattern)
    if (!$match.Success) {
        return "n/a"
    }

    return $match.Groups[1].Value.Trim()
}

function Get-MavenTotalTime {
    param([string]$LogPath)

    if ([string]::IsNullOrWhiteSpace($LogPath) -or !(Test-Path -Path $LogPath)) {
        return "n/a"
    }

    $line = Select-String -Path $LogPath -Pattern "Total time:" | Select-Object -Last 1
    if ($null -eq $line) {
        return "n/a"
    }

    $match = [regex]::Match($line.Line, "Total time:\s+(.+)$")
    if (!$match.Success) {
        return "n/a"
    }

    return $match.Groups[1].Value.Trim()
}

function Get-HitMiss {
    param(
        [string]$LogPath,
        [string]$Prefix,
        [string]$Key
    )

    if ([string]::IsNullOrWhiteSpace($LogPath) -or !(Test-Path -Path $LogPath)) {
        return "n/a"
    }

    $escapedPrefix = [regex]::Escape($Prefix)
    $line = Select-String -Path $LogPath -Pattern $escapedPrefix | Select-Object -Last 1
    if ($null -eq $line) {
        return "n/a"
    }

    $pattern = "{0}=([0-9]+/[0-9]+)" -f [regex]::Escape($Key)
    $match = [regex]::Match($line.Line, $pattern)
    if (!$match.Success) {
        return "n/a"
    }

    return $match.Groups[1].Value.Trim()
}

function Build-ColumnValues {
    param([string]$LogPath)

    return [ordered]@{
        MavenTotalTime = Get-MavenTotalTime -LogPath $LogPath
        ServiceTotalMs = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 timingMs:" -MetricName "total"
        RowsTotal = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 metrics:" -MetricName "rows total"
        RowsProcessed = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 metrics:" -MetricName "processed"
        RowsSkippedInferenceMiss = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 metrics:" -MetricName "skippedInferenceMiss"
        RowExceptions = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 metrics:" -MetricName "rowExceptions"
        ClubLookupHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 lookup:" -Key "club hit/miss"
        PracticionerLookupHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 lookup:" -Key "practicioner hit/miss"
        ClubMemberCacheHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 cache:" -Key "clubMember hit/miss"
        SeasonPlayerCacheHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 cache:" -Key "seasonPlayer hit/miss"
        SeasonPlayerResultCacheHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 cache:" -Key "seasonPlayerResult hit/miss"
        SingleMatchCacheHitMiss = Get-HitMiss -LogPath $LogPath -Prefix "BCNESA Phase1 cache:" -Key "singleMatch hit/miss"
        ClubMemberSaved = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 saves:" -MetricName "clubMember"
        SeasonPlayerSaved = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 saves:" -MetricName "seasonPlayer"
        SeasonPlayerResultSaved = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 saves:" -MetricName "seasonPlayerResult"
        PlayersSingleMatchSaved = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 saves:" -MetricName "playersSingleMatch"
        SaveSkippedNoChange = Get-MetricLineValue -LogPath $LogPath -Prefix "BCNESA Phase1 saves:" -MetricName "saveSkippedNoChange"
    }
}

$baselineRun1 = Build-ColumnValues -LogPath $BaselineRun1Log
$baselineRun2 = Build-ColumnValues -LogPath $BaselineRun2Log
$phase1Run1 = Build-ColumnValues -LogPath $Phase1Run1Log
$phase1Run2 = Build-ColumnValues -LogPath $Phase1Run2Log

$table = @(
    "| Metric | Baseline Run1 | Baseline Run2 | Phase1 Run1 | Phase1 Run2 |",
    "|---|---:|---:|---:|---:|",
    "| Maven total runtime | $($baselineRun1.MavenTotalTime) | $($baselineRun2.MavenTotalTime) | $($phase1Run1.MavenTotalTime) | $($phase1Run2.MavenTotalTime) |",
    "| Phase1 total (service timing, ms) | $($baselineRun1.ServiceTotalMs) | $($baselineRun2.ServiceTotalMs) | $($phase1Run1.ServiceTotalMs) | $($phase1Run2.ServiceTotalMs) |",
    "| rowsTotal | $($baselineRun1.RowsTotal) | $($baselineRun2.RowsTotal) | $($phase1Run1.RowsTotal) | $($phase1Run2.RowsTotal) |",
    "| rowsProcessed | $($baselineRun1.RowsProcessed) | $($baselineRun2.RowsProcessed) | $($phase1Run1.RowsProcessed) | $($phase1Run2.RowsProcessed) |",
    "| rowsSkippedInferenceMiss | $($baselineRun1.RowsSkippedInferenceMiss) | $($baselineRun2.RowsSkippedInferenceMiss) | $($phase1Run1.RowsSkippedInferenceMiss) | $($phase1Run2.RowsSkippedInferenceMiss) |",
    "| rowExceptions | $($baselineRun1.RowExceptions) | $($baselineRun2.RowExceptions) | $($phase1Run1.RowExceptions) | $($phase1Run2.RowExceptions) |",
    "| club hit/miss | $($baselineRun1.ClubLookupHitMiss) | $($baselineRun2.ClubLookupHitMiss) | $($phase1Run1.ClubLookupHitMiss) | $($phase1Run2.ClubLookupHitMiss) |",
    "| practicioner hit/miss | $($baselineRun1.PracticionerLookupHitMiss) | $($baselineRun2.PracticionerLookupHitMiss) | $($phase1Run1.PracticionerLookupHitMiss) | $($phase1Run2.PracticionerLookupHitMiss) |",
    "| clubMember cache hit/miss | $($baselineRun1.ClubMemberCacheHitMiss) | $($baselineRun2.ClubMemberCacheHitMiss) | $($phase1Run1.ClubMemberCacheHitMiss) | $($phase1Run2.ClubMemberCacheHitMiss) |",
    "| seasonPlayer cache hit/miss | $($baselineRun1.SeasonPlayerCacheHitMiss) | $($baselineRun2.SeasonPlayerCacheHitMiss) | $($phase1Run1.SeasonPlayerCacheHitMiss) | $($phase1Run2.SeasonPlayerCacheHitMiss) |",
    "| seasonPlayerResult cache hit/miss | $($baselineRun1.SeasonPlayerResultCacheHitMiss) | $($baselineRun2.SeasonPlayerResultCacheHitMiss) | $($phase1Run1.SeasonPlayerResultCacheHitMiss) | $($phase1Run2.SeasonPlayerResultCacheHitMiss) |",
    "| singleMatch cache hit/miss | $($baselineRun1.SingleMatchCacheHitMiss) | $($baselineRun2.SingleMatchCacheHitMiss) | $($phase1Run1.SingleMatchCacheHitMiss) | $($phase1Run2.SingleMatchCacheHitMiss) |",
    "| clubMemberSaved | $($baselineRun1.ClubMemberSaved) | $($baselineRun2.ClubMemberSaved) | $($phase1Run1.ClubMemberSaved) | $($phase1Run2.ClubMemberSaved) |",
    "| seasonPlayerSaved | $($baselineRun1.SeasonPlayerSaved) | $($baselineRun2.SeasonPlayerSaved) | $($phase1Run1.SeasonPlayerSaved) | $($phase1Run2.SeasonPlayerSaved) |",
    "| seasonPlayerResultSaved | $($baselineRun1.SeasonPlayerResultSaved) | $($baselineRun2.SeasonPlayerResultSaved) | $($phase1Run1.SeasonPlayerResultSaved) | $($phase1Run2.SeasonPlayerResultSaved) |",
    "| playersSingleMatchSaved | $($baselineRun1.PlayersSingleMatchSaved) | $($baselineRun2.PlayersSingleMatchSaved) | $($phase1Run1.PlayersSingleMatchSaved) | $($phase1Run2.PlayersSingleMatchSaved) |",
    "| saveSkippedNoChange | $($baselineRun1.SaveSkippedNoChange) | $($baselineRun2.SaveSkippedNoChange) | $($phase1Run1.SaveSkippedNoChange) | $($phase1Run2.SaveSkippedNoChange) |"
)

$tableText = $table -join [Environment]::NewLine

if (![string]::IsNullOrWhiteSpace($OutputPath)) {
    $parentDir = Split-Path -Path $OutputPath -Parent
    if (![string]::IsNullOrWhiteSpace($parentDir) -and !(Test-Path -Path $parentDir)) {
        New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
    }
    Set-Content -Path $OutputPath -Value $tableText -Encoding UTF8
    Write-Host "Comparison table written to $OutputPath"
}

Write-Host $tableText


