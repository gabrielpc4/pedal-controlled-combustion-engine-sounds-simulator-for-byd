<#
.SYNOPSIS
Runs 100 completed native sound-family swaps and checks stabilized retained memory through ADB.

.DESCRIPTION
Installs the exact debug APK, imports the private catalog and selected .aclib packs, backgrounds the
UI, and switches only after each target family reports ACTIVE. Every 20 completed switches it returns
to the same anchor family, requests test-only off-audio-thread GC, and records dumpsys meminfo plus
runtime diagnostics. The run fails on decoded-byte drift, retained PSS/native growth, stalled audio,
over-range samples, steady underruns, 256-frame-normalized steady renderer p99 >= 1.5 ms, or a
pack-transition render that exceeds the actual AudioTrack burst deadline. Overall lifetime p99/max
remain in the report and are not disguised as steady-state measurements.

.EXAMPLE
.\tools\run-emulator-car-switch-memory.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackDirectory D:\private\aclib\packs `
  -Serial emulator-5554

.EXAMPLE
.\tools\run-emulator-car-switch-memory.ps1 -SelfTest
#>
[CmdletBinding(DefaultParameterSetName = "Run")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$ApkPath,

    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$CatalogPath,

    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$PackDirectory,

    [Parameter(ParameterSetName = "Run")]
    [string]$Serial,

    [Parameter(ParameterSetName = "Run")]
    [ValidateRange(2, 153)]
    [int]$FamilyLimit = 153,

    [Parameter(ParameterSetName = "Run")]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$CapturePlanSha256,

    [Parameter(ParameterSetName = "Run")]
    [ValidateRange(100, 1000)]
    [int]$SwitchCount = 100,

    [Parameter(ParameterSetName = "Run")]
    [ValidateRange(0, 100)]
    [int]$WarmupSwitches = 10,

    [Parameter(ParameterSetName = "Run")]
    [ValidateRange(15, 600)]
    [int]$TimeoutSeconds = 180,

    [Parameter(ParameterSetName = "Run")]
    [string]$OutputPath,

    [Parameter(Mandatory = $true, ParameterSetName = "SelfTest")]
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$packageName = "com.gabrielpc.enginesoundsimulator"
$activityComponent = "$packageName/.MainActivity"
$debugTag = "BYDDriveDebug"
$adbDrivePath = Join-Path $PSScriptRoot "adb-drive.ps1"
$script:AdbExecutable = $null
$script:SelectedSerial = $null
$script:RuntimeMayBeRunning = $false
$script:OriginalPath = $env:PATH

function Assert-Stress {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw "MEMORY ACCEPTANCE FAILED: $Message" }
}

function ConvertFrom-Meminfo {
    param([Parameter(Mandatory = $true)][string]$Text)
    function Read-SummaryKilobytes([string]$Label) {
        $match = [regex]::Match(
            $Text,
            "(?m)^\s*$([regex]::Escape($Label)):\s+([0-9,]+)(?:\s|$)"
        )
        Assert-Stress $match.Success "dumpsys meminfo omitted '$Label'"
        return [long]$match.Groups[1].Value.Replace(',', '')
    }
    return [pscustomobject]@{
        TotalPssKb = Read-SummaryKilobytes "TOTAL PSS"
        JavaHeapPssKb = Read-SummaryKilobytes "Java Heap"
        NativeHeapPssKb = Read-SummaryKilobytes "Native Heap"
    }
}

function Get-NextFamilyIndex {
    param(
        [Parameter(Mandatory = $true)][int]$CompletedSwitchNumber,
        [Parameter(Mandatory = $true)][int]$CurrentIndex,
        [Parameter(Mandatory = $true)][int]$FamilyCount
    )
    Assert-Stress ($FamilyCount -ge 2) "At least two sound families are required"
    if ($CompletedSwitchNumber % 20 -eq 0) { return 0 }
    # Anchor checkpoints do not consume a non-anchor family ordinal. Otherwise a family whose
    # ordinal lands on every 20th switch could be skipped forever (for example 20 of 21).
    $nonAnchorOrdinal = $CompletedSwitchNumber - [int][Math]::Floor($CompletedSwitchNumber / 20.0)
    $candidate = 1 + (($nonAnchorOrdinal - 1) % ($FamilyCount - 1))
    if ($candidate -eq $CurrentIndex) { return 0 }
    return $candidate
}

function Test-TargetPublication([hashtable]$Snapshot, [string]$CarId) {
    $decodedBytes = 0L
    return $Snapshot['car'] -ceq $CarId -and
        $Snapshot['pack_car'] -ceq $CarId -and
        $Snapshot['pack_status'] -ceq 'ACTIVE' -and
        [long]::TryParse([string]$Snapshot['decoded_bytes'], [ref]$decodedBytes) -and
        $decodedBytes -gt 0
}

function Invoke-SelfTest {
    $mock = @'
 App Summary
           Java Heap:    12345                          20000
         Native Heap:    67,890                         70000
           TOTAL PSS:   101234            TOTAL RSS:   220000
'@
    $memory = ConvertFrom-Meminfo $mock
    Assert-Stress ($memory.TotalPssKb -eq 101234) "TOTAL PSS parser failed"
    Assert-Stress ($memory.JavaHeapPssKb -eq 12345) "Java Heap parser failed"
    Assert-Stress ($memory.NativeHeapPssKb -eq 67890) "Native Heap parser failed"
    $oldProfileStillActive = @{
        car = 'new-car'; pack_car = 'old-car'; pack_status = 'ACTIVE'; decoded_bytes = '1234'
    }
    $newProfilePublished = @{
        car = 'new-car'; pack_car = 'new-car'; pack_status = 'ACTIVE'; decoded_bytes = '1234'
    }
    Assert-Stress (-not (Test-TargetPublication $oldProfileStillActive 'new-car')) (
        "Old ACTIVE renderer falsely satisfied target publication"
    )
    Assert-Stress (Test-TargetPublication $newProfilePublished 'new-car') (
        "Published target renderer was not recognized"
    )
    foreach ($families in 2..153) {
        $current = 0
        for ($switch = 1; $switch -le 100; $switch++) {
            $next = Get-NextFamilyIndex $switch $current $families
            Assert-Stress ($next -ne $current) "Schedule repeated family $current at switch $switch"
            $current = $next
            if ($switch % 20 -eq 0) {
                Assert-Stress ($current -eq 0) "Schedule did not normalize at switch $switch"
            }
        }
        $covered = [Collections.Generic.HashSet[int]]::new()
        $null = $covered.Add(0)
        $current = 0
        for ($switch = 1; $switch -le 1000; $switch++) {
            $current = Get-NextFamilyIndex $switch $current $families
            $null = $covered.Add($current)
        }
        Assert-Stress ($covered.Count -eq $families) (
            "1000-switch schedule covered $($covered.Count)/$families families"
        )
    }
    Write-Host "PASS: car-switch memory harness parser/schedule self-test" -ForegroundColor Green
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

function Resolve-RequiredFile([string]$Path, [string]$Label) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    Assert-Stress ((Test-Path -LiteralPath $resolved -PathType Leaf) -and
        (Get-Item -LiteralPath $resolved).Length -gt 0) "$Label is missing or empty: $Path"
    return $resolved
}

function Resolve-Adb {
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    $configuredSdkRoot = $null
    $sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=(.+)$' } | Select-Object -First 1
    if ($sdkLine -and $sdkLine -match '^sdk\.dir=(.+)$') {
        $configuredSdkRoot = $Matches[1].Replace('\:', ':').Replace('\\', '\')
    }
    $candidate = @(
        (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Source -First 1),
        $(if ($configuredSdkRoot) { Join-Path $configuredSdkRoot "platform-tools\adb.exe" }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe" }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
    Assert-Stress (-not [string]::IsNullOrWhiteSpace($candidate)) "adb was not found"
    return [string]$candidate
}

function Invoke-AdbCapture {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $raw = @(& $script:AdbExecutable -s $script:SelectedSerial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $lines = @($raw | ForEach-Object { $_.ToString() })
    if ($exitCode -ne 0) {
        throw "MEMORY ACCEPTANCE FAILED: adb $($Arguments -join ' ') exited $exitCode. $($lines -join ' ')"
    }
    return $lines
}

function Get-DebugLines {
    return @(Invoke-AdbCapture logcat "-d" "-v" "epoch" "-s" "$debugTag`:I" "*:S")
}

function Wait-LogResult([int]$Cursor, [string]$Command) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $escaped = [regex]::Escape($Command)
    do {
        $all = @(Get-DebugLines)
        Assert-Stress ($all.Count -ge $Cursor) "Debug log rotated during memory acceptance"
        $fresh = @($all | Select-Object -Skip $Cursor)
        $failure = $fresh | Where-Object { $_ -match "command=$escaped result=error(?:\s|$)" } |
            Select-Object -Last 1
        if ($failure) { throw "MEMORY ACCEPTANCE FAILED: $failure" }
        $success = $fresh | Where-Object { $_ -match "command=$escaped result=ok(?:\s|$)" } |
            Select-Object -Last 1
        if ($success) { return }
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "MEMORY ACCEPTANCE FAILED: timed out waiting for debug command '$Command'"
}

function Invoke-DriveAndWait([string]$DriveCommand, [string]$DebugCommand, [hashtable]$Parameters = @{}) {
    $cursor = @(Get-DebugLines).Count
    $arguments = @{
        Command = $DriveCommand
        Serial = $script:SelectedSerial
    }
    foreach ($entry in $Parameters.GetEnumerator()) {
        $arguments[[string]$entry.Key] = [string]$entry.Value
    }
    $null = @(& $adbDrivePath @arguments 2>&1)
    Assert-Stress ($LASTEXITCODE -eq 0) "adb-drive '$DriveCommand' failed"
    Wait-LogResult $cursor $DebugCommand
}

function Invoke-BulkPackImportAndWait([string[]]$PackPaths) {
    Assert-Stress ($PackPaths.Count -gt 0) "Bulk pack import list is empty"
    $cursor = @(Get-DebugLines).Count
    $arguments = @{
        Command = 'import-packs'
        PackPath = $PackPaths
        Serial = $script:SelectedSerial
    }
    $null = @(& $adbDrivePath @arguments 2>&1)
    Assert-Stress ($LASTEXITCODE -eq 0) "adb-drive 'import-packs' failed"
    Wait-LogResult $cursor 'import_packs'
}

function ConvertFrom-SnapshotLine([string]$Line) {
    $fields = @{}
    # Diagnostic field names include percentile suffixes such as render_p99_us.
    foreach ($match in [regex]::Matches($Line, "(?<key>[a-z0-9_]+)=(?<value>\S+)")) {
        $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
    }
    $fields['_line'] = $Line
    return $fields
}

function Get-Snapshot {
    $cursor = @(Get-DebugLines).Count
    $null = @(& $adbDrivePath -Command snapshot -Serial $script:SelectedSerial 2>&1)
    Assert-Stress ($LASTEXITCODE -eq 0) "adb-drive snapshot failed"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $all = @(Get-DebugLines)
        $fresh = @($all | Select-Object -Skip $cursor)
        $line = $fresh | Where-Object { $_ -match 'snapshot reason=log_snapshot(?:\s|$)' } |
            Select-Object -Last 1
        if ($line) { return ConvertFrom-SnapshotLine $line }
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "MEMORY ACCEPTANCE FAILED: timed out waiting for runtime snapshot"
}

function Get-Int64Field([hashtable]$Snapshot, [string]$Name) {
    Assert-Stress ($Snapshot.ContainsKey($Name)) "Snapshot omitted '$Name': $($Snapshot['_line'])"
    $parsed = 0L
    Assert-Stress ([long]::TryParse([string]$Snapshot[$Name], [ref]$parsed)) (
        "Snapshot '$Name' is not an integer: $($Snapshot[$Name])"
    )
    return $parsed
}

function Wait-ActiveCar([string]$CarId) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = Get-Snapshot
        if (Test-TargetPublication $snapshot $CarId) {
            $framesBefore = Get-Int64Field $snapshot 'audio_frames'
            Start-Sleep -Milliseconds 250
            $confirmed = Get-Snapshot
            if ((Test-TargetPublication $confirmed $CarId) -and
                (Get-Int64Field $confirmed 'audio_frames') -gt $framesBefore) {
                return $confirmed
            }
        }
        if ($snapshot['pack_status'] -ceq 'ERROR') {
            throw "MEMORY ACCEPTANCE FAILED: activation failed: $($snapshot['_line'])"
        }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "MEMORY ACCEPTANCE FAILED: '$CarId' did not become ACTIVE"
}

function Get-PackIdentity([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = @($archive.Entries | Where-Object { $_.FullName -ceq 'manifest.json' })
        Assert-Stress ($entry.Count -eq 1) "Pack '$Path' has no unique manifest.json"
        $reader = [IO.StreamReader]::new($entry[0].Open(), [Text.UTF8Encoding]::new($false, $true))
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $familyId = [string]$manifest.familyId
        Assert-Stress ($familyId -cmatch '^[0-9a-f]{64}$') "Pack '$Path' has invalid familyId"
        $schemaVersion = [int]$manifest.schemaVersion
        Assert-Stress ($schemaVersion -eq 2) "Pack '$Path' is not a strict schema-v2 pack"
        $capturePlan = [string]$manifest.provenance.capturePlanSha256
        Assert-Stress ($capturePlan -cmatch '^[0-9a-f]{64}$') (
            "Pack '$Path' has no strict capture-plan SHA-256 provenance"
        )
        return [pscustomobject]@{
            familyId = $familyId
            schemaVersion = $schemaVersion
            capturePlanSha256 = $capturePlan
            packSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    } finally {
        $archive.Dispose()
    }
}

function Get-MemorySample([int]$CompletedSwitches, [string]$CarId) {
    Invoke-DriveAndWait 'stabilize-memory' 'stabilize_memory'
    Start-Sleep -Milliseconds 400
    $before = Get-Snapshot
    Assert-Stress ($before['car'] -ceq $CarId -and $before['pack_status'] -ceq 'ACTIVE') (
        "Memory checkpoint is not on anchor car '$CarId': $($before['_line'])"
    )
    $audioFramesBefore = Get-Int64Field $before 'audio_frames'
    # audio_frames belongs to the currently published renderer and intentionally resets on
    # every family swap. Prove forward progress within this one stable renderer instead of
    # comparing counters from separate anchor instances at different checkpoints.
    Start-Sleep -Milliseconds 250
    $snapshot = Get-Snapshot
    Assert-Stress ($snapshot['car'] -ceq $CarId -and $snapshot['pack_status'] -ceq 'ACTIVE') (
        "Audio-progress probe left anchor car '$CarId': $($snapshot['_line'])"
    )
    $audioFrames = Get-Int64Field $snapshot 'audio_frames'
    Assert-Stress ($audioFrames -gt $audioFramesBefore) (
        "Audio renderer did not advance within checkpoint $CompletedSwitches"
    )
    $memory = ConvertFrom-Meminfo ((Invoke-AdbCapture shell dumpsys meminfo $packageName) -join "`n")
    $framesPerWrite = Get-Int64Field $snapshot 'frames_per_write'
    $sampleRate = Get-Int64Field $snapshot 'sample_rate'
    Assert-Stress ($framesPerWrite -gt 0 -and $sampleRate -gt 0) "Invalid render burst geometry"
    $steadyRenderP99Us = Get-Int64Field $snapshot 'steady_render_p99_us'
    $transitionRenderMaxUs = Get-Int64Field $snapshot 'transition_render_max_us'
    return [pscustomobject]@{
        completedSwitches = $CompletedSwitches
        carId = $CarId
        decodedBytes = Get-Int64Field $snapshot 'decoded_bytes'
        totalPssKb = $memory.TotalPssKb
        javaHeapPssKb = $memory.JavaHeapPssKb
        nativeHeapPssKb = $memory.NativeHeapPssKb
        audioFramesBeforeProbe = $audioFramesBefore
        audioFrames = $audioFrames
        audioFrameDelta = $audioFrames - $audioFramesBefore
        steadyUnderruns = Get-Int64Field $snapshot 'steady_underruns'
        framesPerWrite = $framesPerWrite
        sampleRate = $sampleRate
        renderP99Us = Get-Int64Field $snapshot 'render_p99_us'
        renderP99LowerUs = Get-Int64Field $snapshot 'render_p99_lower_us'
        renderMaxUs = Get-Int64Field $snapshot 'render_max_us'
        renderSamples = Get-Int64Field $snapshot 'render_samples'
        steadyRenderP99Us = $steadyRenderP99Us
        steadyRenderP99LowerUs = Get-Int64Field $snapshot 'steady_render_p99_lower_us'
        steadyRenderMaxUs = Get-Int64Field $snapshot 'steady_render_max_us'
        steadyRenderSamples = Get-Int64Field $snapshot 'steady_render_samples'
        steadyRenderP99Per256Us = [long][Math]::Ceiling($steadyRenderP99Us * 256.0 / $framesPerWrite)
        transitionRenderP99Us = Get-Int64Field $snapshot 'transition_render_p99_us'
        transitionRenderP99LowerUs = Get-Int64Field $snapshot 'transition_render_p99_lower_us'
        transitionRenderMaxUs = $transitionRenderMaxUs
        transitionRenderSamples = Get-Int64Field $snapshot 'transition_render_samples'
        transitionDeadlineUs = [long][Math]::Floor($framesPerWrite * 1000000.0 / $sampleRate)
        overRangeSamples = Get-Int64Field $snapshot 'over_range'
    }
}

$packRecords = @()
$samples = @()
$activatedFamilies = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
try {
    $apk = Resolve-RequiredFile $ApkPath 'APK'
    $catalogPathResolved = Resolve-RequiredFile $CatalogPath 'Catalog'
    $packsRoot = (Resolve-Path -LiteralPath $PackDirectory -ErrorAction Stop).Path
    Assert-Stress (Test-Path -LiteralPath $packsRoot -PathType Container) (
        "PackDirectory is not a directory: $PackDirectory"
    )
    $catalog = Get-Content -LiteralPath $catalogPathResolved -Raw | ConvertFrom-Json
    $packFiles = @(Get-ChildItem -LiteralPath $packsRoot -File -Filter '*.aclib' | Sort-Object Name)
    Assert-Stress ($packFiles.Count -ge 2) "At least two .aclib packs are required"
    foreach ($packFile in $packFiles) {
        $identity = Get-PackIdentity $packFile.FullName
        if ($CapturePlanSha256 -and $identity.capturePlanSha256 -cne $CapturePlanSha256) {
            continue
        }
        $cars = @($catalog.cars | Where-Object { [string]$_.familyId -ceq $identity.familyId })
        Assert-Stress ($cars.Count -gt 0) "Catalog has no car for pack family '$($identity.familyId)'"
        $packRecords += [pscustomobject]@{
            familyId = $identity.familyId
            path = $packFile.FullName
            carId = [string]$cars[0].id
            schemaVersion = $identity.schemaVersion
            capturePlanSha256 = $identity.capturePlanSha256
            packSha256 = $identity.packSha256
        }
    }
    $packRecords = @($packRecords | Sort-Object familyId -Unique | Select-Object -First $FamilyLimit)
    Assert-Stress ($packRecords.Count -ge 2) "FamilyLimit resolved fewer than two unique packs"
    $selectedCapturePlans = @($packRecords.capturePlanSha256 | Sort-Object -Unique)
    Assert-Stress ($selectedCapturePlans.Count -eq 1) (
        "Memory acceptance requires one exact capture-plan boundary; selected: " +
            ($selectedCapturePlans -join ', ')
    )
    $selectedCapturePlan = [string]$selectedCapturePlans[0]

    $script:AdbExecutable = Resolve-Adb
    $env:PATH = "$(Split-Path -Parent $script:AdbExecutable);$env:PATH"
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        $emulators = @(
            & $script:AdbExecutable devices |
                Where-Object { $_ -match '^(emulator-\d+)\s+device$' } |
                ForEach-Object { [regex]::Match($_, '^(emulator-\d+)').Groups[1].Value }
        )
        Assert-Stress ($emulators.Count -eq 1) "Specify -Serial unless one emulator is online"
        $script:SelectedSerial = $emulators[0]
    } else {
        $script:SelectedSerial = $Serial.Trim()
    }
    Assert-Stress (((Invoke-AdbCapture shell getprop ro.kernel.qemu) -join '').Trim() -eq '1') (
        "Memory harness refuses non-emulator targets"
    )
    $null = Invoke-AdbCapture shell cmd media_session volume --stream 3 --set 0
    $volume = (Invoke-AdbCapture shell cmd media_session volume --stream 3 --get) -join "`n"
    Assert-Stress ($volume -match '(?im)volume is 0 in range') "Could not prove STREAM_MUSIC=0"

    $install = (Invoke-AdbCapture install '-r' '-d' '-t' '--bypass-low-target-sdk-block' $apk) -join "`n"
    Assert-Stress ($install -match '(?im)^Success\s*$') "APK installation did not report Success"
    $null = Invoke-AdbCapture shell pm grant --user 0 $packageName android.permission.POST_NOTIFICATIONS
    $null = Invoke-AdbCapture shell am force-stop $packageName
    $launch = (Invoke-AdbCapture shell am start '-W' '-n' $activityComponent) -join "`n"
    Assert-Stress ($launch -match '(?im)^Status:\s+ok\s*$') "Dashboard launch failed"
    $script:RuntimeMayBeRunning = $true

    # Normalize install -r state through the debug command, then recreate the process so the real
    # persisted-startup-mute path prevents both pack decode and AudioTrack construction. This keeps
    # bulk validation/JIT contention out of the audio lifetime while retaining every sample from
    # the deliberate audio start, warm-up swaps, and measured switches.
    Invoke-DriveAndWait 'sound' 'set_sound_enabled' @{ Value = 'false' }
    $null = Invoke-AdbCapture shell am force-stop $packageName
    Start-Sleep -Milliseconds 300
    $launch = (Invoke-AdbCapture shell am start '-W' '-n' $activityComponent) -join "`n"
    Assert-Stress ($launch -match '(?im)^Status:\s+ok\s*$') "Muted dashboard relaunch failed"
    $mutedStartup = Get-Snapshot
    Assert-Stress ($mutedStartup['sound_enabled'] -ceq 'false') "Persisted startup mute was not restored"
    Assert-Stress ($mutedStartup['audio_status'] -ceq 'OFFLINE') "Startup mute opened AudioTrack"
    Assert-Stress ((Get-Int64Field $mutedStartup 'decoded_bytes') -eq 0) "Startup mute decoded a sound family"
    Assert-Stress ((Get-Int64Field $mutedStartup 'sample_rate') -eq 0) "Startup mute configured audio output"

    Write-Host "Importing catalog and $($packRecords.Count) private sound families"
    Invoke-DriveAndWait 'import-catalog' 'import_catalog' @{ Path = $catalogPathResolved }
    Invoke-BulkPackImportAndWait -PackPaths @($packRecords.path)
    Write-Host "  imported $($packRecords.Count)/$($packRecords.Count) in one production batch"

    # STREAM_MUSIC is proven zero above, but the renderer itself must run so swaps, PCM ownership,
    # underruns, and timing are real. Explicitly enable it because install -r preserves a prior mute.
    Invoke-DriveAndWait 'sound' 'set_sound_enabled' @{ Value = 'true' }
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
    $anchor = $packRecords[0]
    Invoke-DriveAndWait 'car' 'select_car' @{ CarId = $anchor.carId }
    $null = Wait-ActiveCar $anchor.carId
    $null = $activatedFamilies.Add([string]$anchor.familyId)
    $currentIndex = 0

    Write-Host "Warming decoder/swap paths with $WarmupSwitches completed switches"
    for ($warm = 1; $warm -le $WarmupSwitches; $warm++) {
        $nextIndex = Get-NextFamilyIndex $warm $currentIndex $packRecords.Count
        $target = $packRecords[$nextIndex]
        Invoke-DriveAndWait 'car' 'select_car' @{ CarId = $target.carId }
        $null = Wait-ActiveCar $target.carId
        $null = $activatedFamilies.Add([string]$target.familyId)
        $currentIndex = $nextIndex
    }
    if ($currentIndex -ne 0) {
        Invoke-DriveAndWait 'car' 'select_car' @{ CarId = $anchor.carId }
        $null = Wait-ActiveCar $anchor.carId
        $null = $activatedFamilies.Add([string]$anchor.familyId)
        $currentIndex = 0
    }

    $samples += Get-MemorySample 0 $anchor.carId
    $baselineDecodedBytes = $samples[0].decodedBytes
    Write-Host (
        "Baseline: PSS=$($samples[0].totalPssKb) KiB native=$($samples[0].nativeHeapPssKb) KiB " +
        "decoded=$baselineDecodedBytes"
    )

    for ($switch = 1; $switch -le $SwitchCount; $switch++) {
        $nextIndex = Get-NextFamilyIndex $switch $currentIndex $packRecords.Count
        $target = $packRecords[$nextIndex]
        Invoke-DriveAndWait 'car' 'select_car' @{ CarId = $target.carId }
        $null = Wait-ActiveCar $target.carId
        $null = $activatedFamilies.Add([string]$target.familyId)
        $currentIndex = $nextIndex
        if ($switch % 20 -eq 0) {
            Assert-Stress ($currentIndex -eq 0) "Checkpoint $switch did not return to anchor family"
            $sample = Get-MemorySample $switch $anchor.carId
            Assert-Stress ($sample.decodedBytes -eq $baselineDecodedBytes) (
                "Anchor decoded bytes drifted at switch $switch"
            )
            $samples += $sample
            Write-Host (
                "  $switch/${SwitchCount}: PSS=$($sample.totalPssKb) KiB " +
                "native=$($sample.nativeHeapPssKb) KiB overall-p99=$($sample.renderP99Us) us " +
                "steady-p99/256=$($sample.steadyRenderP99Per256Us) us " +
                "transition-max=$($sample.transitionRenderMaxUs)/$($sample.transitionDeadlineUs) us"
            )
        }
    }

    $baseline = $samples[0]
    $pssAllowanceKb = [Math]::Max(8192L, [long][Math]::Ceiling($baseline.totalPssKb * 0.10))
    $nativeAllowanceKb = [Math]::Max(4096L, [long][Math]::Ceiling($baseline.nativeHeapPssKb * 0.10))
    foreach ($sample in $samples | Select-Object -Skip 1) {
        Assert-Stress ($sample.totalPssKb -le $baseline.totalPssKb + $pssAllowanceKb) (
            "Stabilized PSS grew beyond allowance at switch $($sample.completedSwitches): " +
            "$($sample.totalPssKb) > $($baseline.totalPssKb + $pssAllowanceKb) KiB"
        )
        Assert-Stress ($sample.nativeHeapPssKb -le $baseline.nativeHeapPssKb + $nativeAllowanceKb) (
            "Stabilized native PSS grew beyond allowance at switch $($sample.completedSwitches)"
        )
    }
    $final = $samples[-1]
    Assert-Stress ($final.completedSwitches -eq $SwitchCount) "Final checkpoint is not switch $SwitchCount"
    Assert-Stress ($final.steadyUnderruns -eq 0) "Final steady underruns=$($final.steadyUnderruns)"
    Assert-Stress ($final.overRangeSamples -eq 0) "Final over-range samples=$($final.overRangeSamples)"
    Assert-Stress ($final.steadyRenderP99Per256Us -lt 1500) (
        "Final 256-frame-normalized steady render p99=$($final.steadyRenderP99Per256Us) us"
    )
    Assert-Stress ($final.transitionRenderMaxUs -gt 0) "No pack-transition render sample was recorded"
    Assert-Stress ($final.transitionRenderMaxUs -lt $final.transitionDeadlineUs) (
        "Pack-transition render max=$($final.transitionRenderMaxUs) us exceeds " +
            "$($final.framesPerWrite)-frame deadline=$($final.transitionDeadlineUs) us"
    )

    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $reportDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\reports\emulator'
        $null = New-Item -ItemType Directory -Force -Path $reportDirectory
        $OutputPath = Join-Path $reportDirectory (
            "car-switch-memory-" + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '.json'
        )
    }
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    $outputParent = Split-Path -Parent $resolvedOutput
    if ($outputParent) { $null = New-Item -ItemType Directory -Force -Path $outputParent }
    $report = [ordered]@{
        schema = 'byd-emulator-car-switch-memory-v1'
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        emulator = $script:SelectedSerial
        apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
        catalogSha256 = (Get-FileHash -LiteralPath $catalogPathResolved -Algorithm SHA256).Hash
        familyCount = $packRecords.Count
        activatedFamilyCount = $activatedFamilies.Count
        activatedFamilyIds = @($activatedFamilies | Sort-Object)
        capturePlanSha256 = $selectedCapturePlan
        packs = @($packRecords | ForEach-Object {
            [ordered]@{
                familyId = $_.familyId
                carId = $_.carId
                schemaVersion = $_.schemaVersion
                packSha256 = $_.packSha256
            }
        })
        completedSwitches = $SwitchCount
        warmupSwitches = $WarmupSwitches
        anchorCarId = $anchor.carId
        anchorFamilyId = $anchor.familyId
        pssAllowanceKb = $pssAllowanceKb
        nativeAllowanceKb = $nativeAllowanceKb
        samples = $samples
    }
    [IO.File]::WriteAllText(
        $resolvedOutput,
        ($report | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host "PASS: $SwitchCount completed family switches with stabilized memory" -ForegroundColor Green
    Write-Host "  report: $resolvedOutput"
    Write-Host "  strict boundary: $($packRecords.Count) schema-v2 families, plan $selectedCapturePlan"
    Write-Host "  activated coverage: $($activatedFamilies.Count)/$($packRecords.Count) families"
    Write-Host "  total PSS: $($baseline.totalPssKb) -> $($final.totalPssKb) KiB"
    Write-Host "  native PSS: $($baseline.nativeHeapPssKb) -> $($final.nativeHeapPssKb) KiB"
    Write-Host "  lifetime render p99/max: $($final.renderP99Us)/$($final.renderMaxUs) us"
    Write-Host "  steady render p99: $($final.steadyRenderP99Per256Us) us per 256 frames"
    Write-Host (
        "  transition render max/deadline: $($final.transitionRenderMaxUs)/" +
            "$($final.transitionDeadlineUs) us at $($final.framesPerWrite) frames"
    )
} finally {
    if ($script:RuntimeMayBeRunning -and $script:AdbExecutable -and $script:SelectedSerial) {
        try {
            $null = @(& $adbDrivePath -Command stop -Serial $script:SelectedSerial 2>&1)
            Start-Sleep -Seconds 3
            $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
        } catch {
            Write-Warning "Could not stop the memory-test runtime: $($_.Exception.Message)"
            try { $null = Invoke-AdbCapture shell am force-stop $packageName } catch { }
        }
    }
    $env:PATH = $script:OriginalPath
}
