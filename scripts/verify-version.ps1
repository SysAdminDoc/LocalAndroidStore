param(
    [string]$Root = (Resolve-Path (Join-Path $PSScriptRoot ".."))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-ProjectFile([string]$Name) {
    $path = Join-Path $Root $Name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Missing project file: $Name"
    }
    return Get-Content -LiteralPath $path -Raw
}

$gradle = Read-ProjectFile "app/build.gradle.kts"
$readme = Read-ProjectFile "README.md"
$changelog = Read-ProjectFile "CHANGELOG.md"
$claude = Read-ProjectFile "CLAUDE.md"
$roadmap = Read-ProjectFile "ROADMAP.md"

$versionMatch = [regex]::Match($gradle, '(?m)^\s*versionName\s*=\s*"(?<value>\d+\.\d+\.\d+)"')
$codeMatch = [regex]::Match($gradle, '(?m)^\s*versionCode\s*=\s*(?<value>\d+)')
if (-not $versionMatch.Success -or -not $codeMatch.Success) {
    throw "Could not extract versionName/versionCode from app/build.gradle.kts"
}
$version = $versionMatch.Groups["value"].Value
$versionCode = $codeMatch.Groups["value"].Value

$errors = [System.Collections.Generic.List[string]]::new()
function Compare-Value([string]$Label, [string]$Actual, [string]$Expected) {
    if ($Actual -ne $Expected) {
        $script:errors.Add("$Label is '$Actual'; expected '$Expected'.")
    }
}

$badge = [regex]::Match($readme, '(?i)img\.shields\.io/badge/version-(?<value>\d+\.\d+\.\d+)')
if ($badge.Success) {
    Compare-Value "README version badge" $badge.Groups["value"].Value $version
} else {
    $errors.Add("README version badge could not be found.")
}

$currentChangelog = [regex]::Match(
    $changelog,
    '(?is)\*\*v(?<version>\d+\.\d+\.\d+)\s+status\*\*.*?versionCode\s*=\s*(?<code>\d+).*?versionName\s*=\s*"(?<name>\d+\.\d+\.\d+)"'
)
if ($currentChangelog.Success) {
    Compare-Value "CHANGELOG current version" $currentChangelog.Groups["version"].Value $version
    Compare-Value "CHANGELOG current versionName" $currentChangelog.Groups["name"].Value $version
    Compare-Value "CHANGELOG current versionCode" $currentChangelog.Groups["code"].Value $versionCode
} else {
    $errors.Add("CHANGELOG current release marker could not be found.")
}

$claudeMarker = [regex]::Match(
    $claude,
    "(?im)^\s*-\s*v$([regex]::Escape($version))\s+\("
)
if (-not $claudeMarker.Success) {
    $errors.Add("CLAUDE.md has no version-history entry for v$version.")
}

$roadmapMarker = [regex]::Match(
    $roadmap,
    '(?im)^\s*(?:current\s+(?:build|version)|state\s+of\s+the\s+repo).*?\bv(?<value>\d+\.\d+\.\d+)\b'
)
if ($roadmapMarker.Success) {
    Compare-Value "ROADMAP current version" $roadmapMarker.Groups["value"].Value $version
} else {
    Write-Output "ROADMAP.md has no current-version marker; active-only roadmap accepted."
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

Write-Output "Version metadata consistent: v$version (versionCode $versionCode)."
