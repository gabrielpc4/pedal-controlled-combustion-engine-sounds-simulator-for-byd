<# Runs syntax and pure mock/parser tests without connecting to ADB. #>
[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$harness = Join-Path $PSScriptRoot "run-emulator-acceptance.ps1"
$memoryHarness = Join-Path $PSScriptRoot "run-emulator-car-switch-memory.ps1"
$visibleLauncher = Join-Path $PSScriptRoot "start-visible-emulator.ps1"
if (-not (Test-Path -LiteralPath $harness -PathType Leaf)) {
    throw "Harness not found: $harness"
}

foreach ($scriptPath in $harness, $memoryHarness, $visibleLauncher) {
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
        throw "Runtime acceptance tool not found: $scriptPath"
    }
    $tokens = $null
    $parseErrors = $null
    $null = [Management.Automation.Language.Parser]::ParseFile(
        $scriptPath,
        [ref]$tokens,
        [ref]$parseErrors
    )
    if ($parseErrors.Count -ne 0) {
        $messages = $parseErrors | ForEach-Object { "$($_.Extent.StartLineNumber): $($_.Message)" }
        throw "PowerShell syntax validation failed for $scriptPath`:`n$($messages -join "`n")"
    }
}

$source = Get-Content -LiteralPath $harness -Raw
foreach ($requiredToken in @(
    "adb-drive.ps1",
    "cmd media_session volume --stream 3 --set 0",
    "ro.kernel.qemu",
    "POST_NOTIFICATIONS",
    "KEYCODE_HOME",
    "core_steps",
    "audio_frames",
    "ui_snapshot_builds",
    "stopped_by_user",
    "expand-notifications",
    "android:id/action0",
    "Get-InstalledFamilyFingerprint",
    "New-CorruptAclibFixture",
    "explicit fresh session after notification Stop",
    "START_STICKY",
    "Invoke-ExactRecentsDismissal",
    "drive-diagnostics.jsonl"
)) {
    if (-not $source.Contains($requiredToken)) {
        throw "Harness contract token is missing: $requiredToken"
    }
}
if ($source -notmatch '(?s)Get-Command adb -CommandType Application -ErrorAction Stop\s*\|\s*Select-Object -First 1') {
    throw "Harness must deterministically select one adb executable when PATH contains duplicates"
}
if ($source -match "(?im)logcat\s+-c(?:\s|$)") {
    throw "Harness must never clear global logcat"
}
if ($source -match "(?i)computer-use|Start-Process|Invoke-Item") {
    throw "Harness contains a forbidden interactive/host-launch path"
}
if ($source -match "(?i)KEYCODE_ENTER|action\.STOP_DRIVE_RUNTIME|am\s+start-?service") {
    throw "Harness bypasses the exact notification action or uses generic dialog input"
}

& $harness -SelfTest
if ($LASTEXITCODE -ne 0) {
    throw "Harness self-test exited $LASTEXITCODE"
}
& $memoryHarness -SelfTest
if ($LASTEXITCODE -ne 0) {
    throw "Memory harness self-test exited $LASTEXITCODE"
}
$visibleSource = Get-Content -LiteralPath $visibleLauncher -Raw
foreach ($visibleToken in @(
    "RestartExisting",
    "MediaVolume = 0",
    "host audio backend",
    "settings put system volume_music_speaker"
)) {
    if (-not $visibleSource.Contains($visibleToken)) {
        throw "Visible emulator launcher contract token is missing: $visibleToken"
    }
}
Write-Host "PASS: harness syntax and mock tests" -ForegroundColor Green
