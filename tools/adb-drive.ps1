<#
.SYNOPSIS
Controls the debug APK's driving runtime through adb without UI automation.

.EXAMPLE
.\tools\adb-drive.ps1 pedals -Throttle 0.72 -Brake 0
.\tools\adb-drive.ps1 mode -Value SIMULATOR
.\tools\adb-drive.ps1 transmission -Value DRIVE
.\tools\adb-drive.ps1 sound -Value false
.\tools\adb-drive.ps1 car -CarId ks_toyota_supra_mkiv
.\tools\adb-drive.ps1 import-catalog -Path C:\private\catalog-v1.json
.\tools\adb-drive.ps1 import-pack -Path C:\private\family.aclib
.\tools\adb-drive.ps1 import-packs -Path C:\private\packs
.\tools\adb-drive.ps1 validate
.\tools\adb-drive.ps1 snapshot

.NOTES
The script resolves adb from PATH, ANDROID_SDK_ROOT, ANDROID_HOME, or the standard
per-user Android SDK location. It talks only to the debug-only control receiver;
release APKs do not expose this input surface.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet(
        "pedals",
        "throttle",
        "brake",
        "reset-pedals",
        "mode",
        "transmission",
        "sound",
        "car",
        "favorite",
        "import-catalog",
        "import-pack",
        "import-packs",
        "audition",
        "mark-crackle",
        "export-diagnostics",
        "stabilize-memory",
        "validate",
        "snapshot",
        "stop"
    )]
    [string]$Command,

    [ValidateRange(0.0, 1.0)]
    [double]$Throttle = 0.0,

    [ValidateRange(0.0, 1.0)]
    [double]$Brake = 0.0,

    [string]$Value,

    [string]$CarId,

    [string]$Path,

    [string[]]$PackPath,

    [switch]$Silent,

    [string]$Serial
)

$packageName = "com.gabrielpc.enginesoundsimulator"
$receiverName = "$packageName/.debug.DriveDebugReceiver"
$actionName = "$packageName.debug.CONTROL"
$configuredSdkRoot = $null
$localProperties = Join-Path $PSScriptRoot "..\local.properties"
$sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
    Where-Object { $_ -match '^sdk\.dir=(.+)$' } |
    Select-Object -First 1
if ($sdkLine -and $sdkLine -match '^sdk\.dir=(.+)$') {
    $configuredSdkRoot = $Matches[1].Replace('\:', ':').Replace('\\', '\')
}
$adbCandidates = @(
    (Get-Command adb -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -First 1),
    $(if ($configuredSdkRoot) { Join-Path $configuredSdkRoot "platform-tools\adb.exe" }),
    $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
    $(if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools\adb.exe" }),
    $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" })
) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -Unique
if (-not $adbCandidates) {
    throw "adb was not found in PATH or a standard Android SDK location"
}
$adbExecutable = [string]($adbCandidates | Select-Object -First 1)
$adbPrefix = @()
if ($Serial) {
    $adbPrefix += @("-s", $Serial)
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adbExecutable @adbPrefix @Arguments
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "adb failed with exit code $LASTEXITCODE"
    }
}

function Send-DriveCommand {
    param([string[]]$Extras)
    $broadcastArguments = @(
        "shell", "am", "broadcast",
        "-a", $actionName,
        "-n", $receiverName,
        "--es", "command", $Extras[0]
    )
    $broadcastArguments += @($Extras | Select-Object -Skip 1)
    Invoke-Adb @broadcastArguments
}

function Stage-DriveImport {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$RemoteName,
        [Parameter(Mandatory = $true)][string]$RuntimeCommand
    )
    $resolved = (Resolve-Path -LiteralPath $SourcePath -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "Import source must be a file: $SourcePath"
    }
    # API 30+ scoped storage can make shell-owned files under Android/data unreadable to the app.
    # Stage through /data/local/tmp, then copy as the debuggable app UID into its private import dir.
    $temporaryPath = "/data/local/tmp/byd-engine-sound-$RemoteName"
    Invoke-Adb push $resolved $temporaryPath
    Invoke-Adb shell run-as $packageName mkdir "-p" files/adb-import
    Invoke-Adb shell run-as $packageName cp $temporaryPath "files/adb-import/$RemoteName"
    $appDataRoot = ((Invoke-Adb shell run-as $packageName pwd) -join "").Trim()
    if (-not $appDataRoot.StartsWith("/data/")) {
        throw "Unexpected run-as data directory: $appDataRoot"
    }
    $remotePath = "$appDataRoot/files/adb-import/$RemoteName"
    Invoke-Adb shell rm "-f" $temporaryPath
    Send-DriveCommand @("$RuntimeCommand", "--es", "file_path", $remotePath)
}

function Stage-DrivePackBatch {
    param(
        [string]$SourceDirectory,
        [string[]]$SourceFiles
    )
    $packFiles = if ($SourceFiles) {
        @($SourceFiles | ForEach-Object {
            $resolvedFile = (Resolve-Path -LiteralPath $_ -ErrorAction Stop).Path
            if (-not (Test-Path -LiteralPath $resolvedFile -PathType Leaf) -or
                [IO.Path]::GetExtension($resolvedFile) -cne '.aclib') {
                throw "Bulk import source must be a lowercase .aclib file: $_"
            }
            Get-Item -LiteralPath $resolvedFile
        } | Sort-Object FullName -Unique)
    } else {
        $resolved = (Resolve-Path -LiteralPath $SourceDirectory -ErrorAction Stop).Path
        if (-not (Test-Path -LiteralPath $resolved -PathType Container)) {
            throw "Bulk import source must be a directory: $SourceDirectory"
        }
        @(Get-ChildItem -LiteralPath $resolved -File -Filter '*.aclib' | Sort-Object Name)
    }
    if ($packFiles.Count -lt 1 -or $packFiles.Count -gt 153) {
        throw "Bulk import requires 1..153 direct .aclib files; found $($packFiles.Count)"
    }

    $batchName = "bulk-$([Guid]::NewGuid().ToString('N'))"
    Invoke-Adb shell run-as $packageName mkdir "-p" "files/adb-import/$batchName"
    $appDataRoot = ((Invoke-Adb shell run-as $packageName pwd) -join "").Trim()
    if (-not $appDataRoot.StartsWith("/data/")) {
        throw "Unexpected run-as data directory: $appDataRoot"
    }
    for ($index = 0; $index -lt $packFiles.Count; $index += 1) {
        $remoteName = "{0:D3}.aclib" -f $index
        $temporaryPath = "/data/local/tmp/byd-engine-sound-$batchName-$remoteName"
        try {
            Invoke-Adb push $packFiles[$index].FullName $temporaryPath
            Invoke-Adb shell run-as $packageName cp $temporaryPath "files/adb-import/$batchName/$remoteName"
        } finally {
            Invoke-Adb shell rm "-f" $temporaryPath
        }
    }
    Send-DriveCommand @(
        "import_packs",
        "--es", "directory_path", "$appDataRoot/files/adb-import/$batchName"
    )
}

switch ($Command) {
    "pedals" {
        Send-DriveCommand @(
            "set_pedals",
            "--ef", "throttle", $Throttle.ToString([Globalization.CultureInfo]::InvariantCulture),
            "--ef", "brake", $Brake.ToString([Globalization.CultureInfo]::InvariantCulture)
        )
    }
    "throttle" {
        Send-DriveCommand @(
            "set_throttle",
            "--ef", "value", $Throttle.ToString([Globalization.CultureInfo]::InvariantCulture)
        )
    }
    "brake" {
        Send-DriveCommand @(
            "set_brake",
            "--ef", "value", $Brake.ToString([Globalization.CultureInfo]::InvariantCulture)
        )
    }
    "reset-pedals" { Send-DriveCommand @("reset_pedals") }
    "mode" {
        if (-not $Value) { throw "mode requires -Value AUTO, SIMULATOR, or VEHICLE" }
        Send-DriveCommand @("set_input_mode", "--es", "value", $Value)
    }
    "transmission" {
        if (-not $Value) { throw "transmission requires -Value PARK, NEUTRAL, or DRIVE" }
        Send-DriveCommand @("set_transmission", "--es", "value", $Value)
    }
    "sound" {
        if ($Value -notin @("true", "false")) { throw "sound requires -Value true or false" }
        Send-DriveCommand @("set_sound_enabled", "--ez", "value", $Value)
    }
    "car" {
        if (-not $CarId) { throw "car requires -CarId <official installed car id>" }
        Send-DriveCommand @("select_car", "--es", "car_id", $CarId)
    }
    "favorite" {
        if (-not $CarId) { throw "favorite requires -CarId <official car id>" }
        Send-DriveCommand @("toggle_favorite", "--es", "car_id", $CarId)
    }
    "import-catalog" {
        if (-not $Path) { throw "import-catalog requires -Path <catalog-v1.json>" }
        Stage-DriveImport -SourcePath $Path -RemoteName "catalog-v1.json" -RuntimeCommand "import_catalog"
    }
    "import-pack" {
        if (-not $Path) { throw "import-pack requires -Path <family.aclib>" }
        Stage-DriveImport -SourcePath $Path -RemoteName "family.aclib" -RuntimeCommand "import_pack"
    }
    "import-packs" {
        if (-not $Path -and -not $PackPath) {
            throw "import-packs requires -Path <directory> or -PackPath <pack-file-list>"
        }
        if ($Path -and $PackPath) { throw "Use either -Path or -PackPath, not both" }
        Stage-DrivePackBatch -SourceDirectory $Path -SourceFiles $PackPath
    }
    "audition" { Send-DriveCommand @("audition_pops_bangs") }
    "mark-crackle" { Send-DriveCommand @("mark_crackle") }
    "export-diagnostics" { Send-DriveCommand @("export_diagnostics") }
    "stabilize-memory" { Send-DriveCommand @("stabilize_memory") }
    "validate" {
        $soundEnabled = if ($Silent) { "false" } else { "true" }
        Send-DriveCommand @("run_validation", "--ez", "sound_enabled", $soundEnabled)
    }
    "snapshot" { Send-DriveCommand @("log_snapshot") }
    "stop" { Send-DriveCommand @("stop_runtime") }
}

if ($Command -eq "snapshot") {
    Start-Sleep -Milliseconds 250
    Invoke-Adb logcat "-d" "-t" 20 "-s" "BYDDriveDebug:I" "*:S"
}
