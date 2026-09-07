[CmdletBinding()]
param(
    [int]$EmulatorPort = 5580,
    [string]$Api26Avd = "LAS_API_26",
    [string]$Api35Avd = "Aura_API_35",
    [string]$Api37Avd = "OpenTasker_API_37",
    [ValidateSet(26, 35, 37)]
    [int[]]$Apis = @(26, 35, 37)
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
$sdkRoot = $sdkLine.Substring("sdk.dir=".Length).Replace("\:", ":").Replace("/", "\")
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
) | Where-Object { $_.Api -in $Apis }
$api37TestGroups = @(
    (@(
        "com.sysadmin.lasstore.data.ApkInspectorInstrumentedTest"
        "com.sysadmin.lasstore.data.AppIdCacheInstrumentedTest"
        "com.sysadmin.lasstore.data.InstallAuditLogInstrumentedTest"
        "com.sysadmin.lasstore.data.PackageVisibilityInstrumentedTest"
        "com.sysadmin.lasstore.data.SupportBundleInstrumentedTest"
    ) -join ",")
    (@(
        "com.sysadmin.lasstore.install.BackgroundSchedulingInstrumentedTest"
        "com.sysadmin.lasstore.install.ForegroundInstallStoreInstrumentedTest"
        "com.sysadmin.lasstore.install.InstallResultReceiverInstrumentedTest"
        "com.sysadmin.lasstore.ui.PseudolocaleInstrumentedTest"
    ) -join ",")
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#primaryAndOverflowActionsExposeTalkBackSemantics"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#cardBodyExposesDpadPrimaryAction"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#cardRemainsOperableAtTwoHundredPercentFontScale"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#brandWrapsAndRefreshRemainsReachableAtTwoHundredPercentRtl"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#rightToLeftLayoutKeepsPrimaryActionsReachable"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#trustRecoveryCannotExposeEnabledOneTapReplacement"
    "com.sysadmin.lasstore.ui.catalog.CatalogAccessibilityInstrumentedTest#installFailureIsExposedAsOneDeduplicatedLiveAnnouncement"
    "com.sysadmin.lasstore.ui.catalog.PublisherTrustRecoveryDialogInstrumentedTest"
    "com.sysadmin.lasstore.ui.log.LogScreenInstrumentedTest"
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
                    "-no-window",
                    "-no-audio",
                    "-no-boot-anim",
                    "-gpu", "swiftshader"
                ) `
                -WindowStyle Hidden `
                -PassThru
            Wait-ForEmulator -Serial $serial -ExpectedApi $target.Api
            $env:ANDROID_SERIAL = $serial
            if ($target.Api -eq 37) {
                for ($groupIndex = 0; $groupIndex -lt $api37TestGroups.Count; $groupIndex++) {
                    if ($groupIndex -gt 0) {
                        & $adb -s $serial reboot
                        if ($LASTEXITCODE -ne 0) {
                            throw "Could not reboot $serial between API 37 test groups."
                        }
                        Start-Sleep -Seconds 2
                        Wait-ForEmulator -Serial $serial -ExpectedApi $target.Api
                    }
                    Write-Host "Trust matrix: API 37 test group $($groupIndex + 1)/$($api37TestGroups.Count)"
                    Invoke-Gradle -Tasks @(
                        "connectedDebugAndroidTest",
                        "-Pandroid.testInstrumentationRunnerArguments.class=$($api37TestGroups[$groupIndex])"
                    )
                }
            } else {
                Invoke-Gradle -Tasks @("connectedDebugAndroidTest")
            }
        } finally {
            if ($emulatorProcess) {
                & $adb -s $serial emu kill 2>$null | Out-Null
                $emulatorProcess.WaitForExit(30000)
            }
        }
    }
    $verifiedApis = @($matrix | ForEach-Object { $_.Api }) -join "/"
    Write-Host "Trust matrix passed: real APIs $verifiedApis and Robolectric APIs 32/33."
} finally {
    $env:ANDROID_SERIAL = $previousSerial
    Pop-Location
}
