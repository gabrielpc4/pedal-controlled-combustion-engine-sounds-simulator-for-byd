<#
.SYNOPSIS
Starts (or reuses) a headless Android emulator with host audio disabled.

.DESCRIPTION
The emulator is launched with no window and no audio backend, then STREAM_MUSIC is
forced to zero after Android finishes booting. This is the companion to adb-drive.ps1
and run-emulator-acceptance.ps1, so driving input and verification need no desktop UI.

.EXAMPLE
.\tools\start-headless-emulator.ps1 -Avd BYD_Seal_1920x1080
#>
[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [string]$Avd = "BYD_Seal_1920x1080",

    [string]$SdkRoot,

    [ValidateRange(30, 600)]
    [int]$BootTimeoutSeconds = 180,

    [switch]$ForceNew
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

    $fromPath = Get-Command $Name -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty Source -First 1
    $candidates = @(
        $fromPath,
        $(if ($configuredSdkRoot) { Join-Path $configuredSdkRoot $RelativePath }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT $RelativePath }),
        $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME $RelativePath }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\$RelativePath" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -Unique
    if (-not $candidates) {
        throw "$Name was not found in PATH or a standard Android SDK location"
    }
    return [string]($candidates | Select-Object -First 1)
}

$adb = Resolve-AndroidTool -Name "adb" -RelativePath "platform-tools\adb.exe"
$emulator = Resolve-AndroidTool -Name "emulator" -RelativePath "emulator\emulator.exe"

function Get-OnlineEmulators {
    @(
        & $adb devices |
            Where-Object { $_ -match "^(emulator-\d+)\s+device$" } |
            ForEach-Object { [regex]::Match($_, "^(emulator-\d+)").Groups[1].Value }
    )
}

$online = @(Get-OnlineEmulators)
if ($online.Count -gt 0 -and -not $ForceNew) {
    $safeHostProcess = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Where-Object {
            $_.Name -match '^(emulator|qemu-system-.+)\.exe$' -and
            $_.CommandLine -match '(?i)(?:^|\s)-no-window(?:\s|$)' -and
            $_.CommandLine -match '(?i)(?:^|\s)-no-audio(?:\s|$)'
        } |
        Select-Object -First 1
    if (-not $safeHostProcess) {
        throw (
            "An emulator is already online, but its host process does not prove both -no-window " +
            "and -no-audio. Stop it or explicitly start a separate safe AVD."
        )
    }
    $serial = $online[0]
    Write-Host "Reusing verified -no-window -no-audio emulator $serial"
} else {
    $arguments = @(
        "-avd", $Avd,
        "-no-window",
        "-no-audio",
        "-no-boot-anim",
        "-gpu", "swiftshader_indirect",
        "-no-snapshot-load"
    )
    $process = Start-Process `
        -FilePath $emulator `
        -ArgumentList $arguments `
        -WindowStyle Hidden `
        -PassThru
    Write-Host "Started hidden emulator process $($process.Id) for AVD '$Avd'"

    $watch = [Diagnostics.Stopwatch]::StartNew()
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
    } while ($watch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds)
    if (-not $serial) {
        throw "Timed out waiting for the emulator transport after $BootTimeoutSeconds seconds"
    }
}

$bootWatch = [Diagnostics.Stopwatch]::StartNew()
do {
    $booted = ((& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join "").Trim()
    if ($booted -eq "1") { break }
    Start-Sleep -Milliseconds 500
} while ($bootWatch.Elapsed.TotalSeconds -lt $BootTimeoutSeconds)
if ($booted -ne "1") {
    throw "Timed out waiting for Android to finish booting on $serial"
}

& $adb -s $serial shell cmd media_session volume --stream 3 --set 0 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Failed to mute STREAM_MUSIC on $serial" }
$volume = (& $adb -s $serial shell cmd media_session volume --stream 3 --get) -join "`n"
if ($volume -notmatch "(?im)volume is 0 in range") {
    throw "Could not verify STREAM_MUSIC=0 on $serial. Output: $volume"
}

Write-Host "READY: $serial · no window · no audio backend · STREAM_MUSIC=0" -ForegroundColor Green
Write-Output $serial
