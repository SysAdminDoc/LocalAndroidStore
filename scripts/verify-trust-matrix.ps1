[CmdletBinding()]
param(
    [int]$EmulatorPort = 5580,
    [string]$Api26Avd = "LAS_API_26",
    [string]$Api35Avd = "Aura_API_35",
    [string]$Api37Avd = "OpenTasker_API_37"
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$localProperties = Join-Path $repositoryRoot "local.properties"
if (-not (Test-Path -LiteralPath $localProperties)) {
    throw "local.properties is required to locate the Android SDK."
}
$sdkLine = Get-Content -LiteralPath $localProperties |
    Where-Object { $_ -like "sdk.dir=*" } |
    Select-Object -First 1
if (-not $sdkLine) {
    throw "local.properties does not define sdk.dir."
}
$sdkRoot = $sdkLine.Substring("sdk.dir=".Length).Replace("/", "\")
$adb = Join-Path $sdkRoot "platform-tools\adb.exe"
$emulator = Join-Path $sdkRoot "emulator\emulator.exe"
$gradle = Join-Path $repositoryRoot "gradlew.bat"
foreach ($requiredTool in @($adb, $emulator, $gradle)) {
    if (-not (Test-Path -LiteralPath $requiredTool)) {
        throw "Required tool not found: $requiredTool"
    }
}

$matrix = @(
    [pscustomobject]@{ Api = 26; Avd = $Api26Avd },
    [pscustomobject]@{ Api = 35; Avd = $Api35Avd },
    [pscustomobject]@{ Api = 37; Avd = $Api37Avd }
)
$installedAvds = @(& $emulator -list-avds)
$missing = @($matrix | Where-Object { $_.Avd -notin $installedAvds })
if ($missing.Count -gt 0) {
    $description = ($missing | ForEach-Object { "API $($_.Api): $($_.Avd)" }) -join ", "
    throw "Missing required AVDs: $description"
}

function Invoke-Gradle {
    param([Parameter(Mandatory = $true)][string[]]$Tasks)

    & $gradle @Tasks --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed: $($Tasks -join ' ')"
    }
}

function Wait-ForEmulator {
    param(
        [Parameter(Mandatory = $true)][string]$Serial,
        [Parameter(Mandatory = $true)][int]$ExpectedApi
    )

    $deadline = [DateTimeOffset]::UtcNow.AddMinutes(3)
    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $state = & $adb -s $Serial get-state 2>$null
        $booted = & $adb -s $Serial shell getprop sys.boot_completed 2>$null
        if ($state -eq "device" -and $booted -eq "1") {
            $actualApi = & $adb -s $Serial shell getprop ro.build.version.sdk
            if ([int]$actualApi -ne $ExpectedApi) {
                throw "$Serial booted API $actualApi; expected API $ExpectedApi."
            }
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "$Serial did not finish booting within three minutes."
}

$serial = "emulator-$EmulatorPort"
$previousSerial = $env:ANDROID_SERIAL

Push-Location $repositoryRoot
try {
    Write-Host "Trust matrix: JVM (including Robolectric APIs 32/33), lint, and debug APK"
    Invoke-Gradle -Tasks @("testDebugUnitTest", "lintDebug", "assembleDebug")

    foreach ($target in $matrix) {
        $existingState = & $adb -s $serial get-state 2>$null
        if ($existingState -eq "device") {
            throw "$serial is already in use; choose another -EmulatorPort."
        }

        Write-Host "Trust matrix: API $($target.Api) using $($target.Avd)"
        $emulatorProcess = $null
        try {
            $emulatorProcess = Start-Process `
                -FilePath $emulator `
                -ArgumentList @(
                    "-avd", $target.Avd,
                    "-port", $EmulatorPort,
                    "-no-snapshot",
                    "-no-audio",
                    "-no-boot-anim",
                    "-gpu", "swiftshader_indirect"
                ) `
                -WindowStyle Hidden `
                -PassThru
            Wait-ForEmulator -Serial $serial -ExpectedApi $target.Api
            $env:ANDROID_SERIAL = $serial
            Invoke-Gradle -Tasks @("connectedDebugAndroidTest")
        } finally {
            if ($emulatorProcess) {
                & $adb -s $serial emu kill 2>$null | Out-Null
                $emulatorProcess.WaitForExit(30000)
            }
        }
    }
    Write-Host "Trust matrix passed: real APIs 26/35/37 and Robolectric APIs 32/33."
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    Pop-Location
}
