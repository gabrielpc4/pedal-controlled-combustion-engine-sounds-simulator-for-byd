<#
.SYNOPSIS
Starts the BYD test AVD with a real window and host audio backend.

.DESCRIPTION
Unlike start-headless-emulator.ps1, this launcher intentionally keeps the emulator window and
audio backend available. Android STREAM_MUSIC is set to the explicit -MediaVolume value only after
boot. The safe default is zero; pass -MediaVolume 1 for a quiet audible check.

.EXAMPLE
.\tools\start-visible-emulator.ps1 -Avd BYD_Seal_1920x1080 -MediaVolume 1 -RestartExisting
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$Avd = "BYD_Seal_1920x1080",

    [string]$SdkRoot,

    [ValidateRange(0, 15)]
    [int]$MediaVolume = 0,

    [ValidateRange(30, 600)]
    [int]$BootTimeoutSeconds = 180,

    [switch]$RestartExisting
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$configuredSdkRoot = $SdkRoot
if (-not $configuredSdkRoot) {
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    $sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=(.+)$' } |
        Select-Object -First 1
    if ($sdkLine -and $sdkLine -match '^sdk\.dir=(.+)$') {
        $configuredSdkRoot = $Matches[1].Replace('\:', ':').Replace('\\', '\')
    }
}

function Resolve-AndroidTool {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    $candidates = @(
        (Get-Command $Name -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Source -First 1),
        $(if ($configuredSdkRoot) { Join-Path $configuredSdkRoot $RelativePath }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT $RelativePath }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME $RelativePath }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\$RelativePath" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -Unique
    if (-not $candidates) { throw "$Name was not found in PATH or a standard Android SDK location" }
    return [string]($candidates | Select-Object -First 1)
}

function Get-OnlineEmulators {
    @(
        & $adb devices |
            Where-Object { $_ -match "^(emulator-\d+)\s+device$" } |
            ForEach-Object { [regex]::Match($_, "^(emulator-\d+)").Groups[1].Value }
    )
}

$adb = Resolve-AndroidTool -Name "adb" -RelativePath "platform-tools\adb.exe"
$emulator = Resolve-AndroidTool -Name "emulator" -RelativePath "emulator\emulator.exe"
$online = @(Get-OnlineEmulators)

if ($online.Count -gt 0 -and $RestartExisting) {
    foreach ($existingSerial in $online) {
        Write-Host "Stopping existing emulator $existingSerial before the explicit visible restart"
        & $adb -s $existingSerial emu kill | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Could not stop existing emulator $existingSerial" }
    }
    $stopWatch = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 250
        $online = @(Get-OnlineEmulators)
    } while ($online.Count -gt 0 -and $stopWatch.Elapsed.TotalSeconds -lt 30)
    if ($online.Count -gt 0) { throw "Existing emulator did not stop within 30 seconds" }
}

if ($online.Count -gt 0) {
    $visibleProcess = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^(emulator|qemu-system-.+)\.exe$' -and
            $_.CommandLine -notmatch '(?i)(?:^|\s)-no-window(?:\s|$)' -and
            $_.CommandLine -notmatch '(?i)(?:^|\s)-no-audio(?:\s|$)'
        } | Select-Object -First 1
    if (-not $visibleProcess) {
        throw (
            "An emulator is online but its host process is headless or audio-disabled. " +
            "Pass -RestartExisting to replace it explicitly."
        )
    }
    $serial = $online[0]
    Write-Host "Reusing visible/audio-capable emulator $serial"
} else {
    $arguments = @(
        "-avd", $Avd,
        "-no-boot-anim",
        "-gpu", "host",
        "-no-snapshot-load"
    )
    # A visible emulator is the interactive program requested by this script; do not hide its window.
    $process = Start-Process -FilePath $emulator -ArgumentList $arguments -PassThru
    Write-Host "Started visible emulator process $($process.Id) for AVD '$Avd'"

    $transportWatch = [Diagnostics.Stopwatch]::StartNew()
    $serial = $null
    do {
        Start-Sleep -Milliseconds 500
        $candidates = @(Get-OnlineEmulators)
        if ($candidates.Count -gt 0) {
            $serial = $candidates[-1]
            break
        }
        if ($process.HasExited) {
            throw "The emulator exited before becoming available (exit code $($process.ExitCode))"
        }
    } while ($transportWatch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds)
    if (-not $serial) { throw "Timed out waiting for emulator transport after $BootTimeoutSeconds seconds" }
}

$bootWatch = [Diagnostics.Stopwatch]::StartNew()
$booted = ""
do {
    $booted = ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join "").Trim()
    if ($booted -eq "1") { break }
    Start-Sleep -Milliseconds 500
} while ($bootWatch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds)
if ($booted -ne "1") { throw "Timed out waiting for Android to boot on $serial" }

& $adb -s $serial shell cmd media_session volume --stream 3 --set $MediaVolume | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to set STREAM_MUSIC=$MediaVolume on $serial" }
$volume = (& $adb -s $serial shell cmd media_session volume --stream 3 --get) -join "`n"
if ($volume -notmatch "(?im)volume is $MediaVolume in range") {
    # Some API 36 images accept AudioManager's request but retain a boot-loaded per-device zero.
    # Persist the exact requested index and reboot this emulator once so AudioService reloads it.
    & $adb -s $serial shell settings put system volume_music $MediaVolume | Out-Null
    & $adb -s $serial shell settings put system volume_music_speaker $MediaVolume | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to persist STREAM_MUSIC=$MediaVolume on $serial" }
    & $adb -s $serial reboot | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Failed to reboot $serial after persisting media volume" }
    & $adb -s $serial wait-for-device | Out-Null
    $retryWatch = [Diagnostics.Stopwatch]::StartNew()
    $booted = ""
    do {
        $booted = ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join "").Trim()
        if ($booted -eq "1") { break }
        Start-Sleep -Milliseconds 500
    } while ($retryWatch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds)
    if ($booted -ne "1") { throw "Timed out after media-volume reboot on $serial" }
    $volume = (& $adb -s $serial shell cmd media_session volume --stream 3 --get) -join "`n"
    if ($volume -notmatch "(?im)volume is $MediaVolume in range") {
        throw "Could not verify STREAM_MUSIC=$MediaVolume on $serial. Output: $volume"
    }
}

Write-Host (
    "READY: $serial · visible window · host audio backend · STREAM_MUSIC=$MediaVolume"
) -ForegroundColor Green
Write-Output $serial
