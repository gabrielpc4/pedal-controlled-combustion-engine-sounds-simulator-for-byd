<#
.SYNOPSIS
Imports one complete strict AC catalog and proves every official car can publish its exact profile.

.DESCRIPTION
This is a debug/emulator acceptance harness, separate from the 100/1000-switch memory harness. It
imports the exact 178-car catalog and 153 strict schema-v2 packs once, then selects every car ID
exactly once. A selection is accepted only after the newly selected car's own decoded renderer is
published and its frame counter advances; the still-playing old ACTIVE renderer cannot satisfy the
gate. The resulting JSON binds APK, catalog, capture plan and every pack by SHA-256.

.EXAMPLE
.\tools\run-emulator-catalog-sweep.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackDirectory D:\private\aclib\packs `
  -CapturePlanSha256 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef `
  -Serial emulator-5554

.EXAMPLE
.\tools\run-emulator-catalog-sweep.ps1 -SelfTest
#>
[CmdletBinding(DefaultParameterSetName = 'Run')]
param(
    [Parameter(Mandatory = $true, ParameterSetName = 'Run')]
    [string]$ApkPath,

    [Parameter(Mandatory = $true, ParameterSetName = 'Run')]
    [string]$CatalogPath,

    [Parameter(Mandatory = $true, ParameterSetName = 'Run')]
    [string]$PackDirectory,

    [Parameter(Mandatory = $true, ParameterSetName = 'Run')]
    [ValidatePattern('^[0-9a-f]{64}$')]
    [string]$CapturePlanSha256,

    [Parameter(ParameterSetName = 'Run')]
    [string]$Serial,

    [Parameter(ParameterSetName = 'Run')]
    [ValidateRange(15, 1800)]
    [int]$TimeoutSeconds = 600,

    [Parameter(ParameterSetName = 'Run')]
    [ValidateRange(1048576, 201326592)]
    [long]$MinimumDeviceSoftBudgetBytes = 33554432,

    [Parameter(ParameterSetName = 'Run')]
    [string]$OutputPath,

    [Parameter(Mandatory = $true, ParameterSetName = 'SelfTest')]
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$packageName = 'com.gabrielpc.enginesoundsimulator'
$activityComponent = "$packageName/.MainActivity"
$debugTag = 'BYDDriveDebug'
$expectedCarCount = 178
$expectedFamilyCount = 153
$adbDrivePath = Join-Path $PSScriptRoot 'adb-drive.ps1'
$script:AdbExecutable = $null
$script:SelectedSerial = $null
$script:RuntimeMayBeRunning = $false
$script:OriginalPath = $env:PATH

function Assert-Sweep([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "CATALOG SWEEP FAILED: $Message" }
}

function ConvertFrom-SnapshotLine([string]$Line) {
    $fields = @{}
    foreach ($match in [regex]::Matches($Line, '(?<key>[a-z0-9_]+)=(?<value>\S+)')) {
        $fields[$match.Groups['key'].Value] = $match.Groups['value'].Value
    }
    $fields['_line'] = $Line
    return $fields
}

function Get-Int64Field([hashtable]$Snapshot, [string]$Name) {
    Assert-Sweep $Snapshot.ContainsKey($Name) "Snapshot omitted '$Name': $($Snapshot['_line'])"
    $parsed = 0L
    Assert-Sweep ([long]::TryParse([string]$Snapshot[$Name], [ref]$parsed)) (
        "Snapshot '$Name' is not an integer: $($Snapshot[$Name])"
    )
    return $parsed
}

function Get-DoubleField([hashtable]$Snapshot, [string]$Name) {
    Assert-Sweep $Snapshot.ContainsKey($Name) "Snapshot omitted '$Name': $($Snapshot['_line'])"
    $parsed = 0.0
    Assert-Sweep ([double]::TryParse(
        [string]$Snapshot[$Name],
        [Globalization.NumberStyles]::Float,
        [Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsed
    )) "Snapshot '$Name' is not a number: $($Snapshot[$Name])"
    return $parsed
}

function Get-BooleanField([hashtable]$Snapshot, [string]$Name) {
    Assert-Sweep $Snapshot.ContainsKey($Name) "Snapshot omitted '$Name': $($Snapshot['_line'])"
    if ($Snapshot[$Name] -ceq 'true') { return $true }
    if ($Snapshot[$Name] -ceq 'false') { return $false }
    throw "CATALOG SWEEP FAILED: Snapshot '$Name' is not true/false: $($Snapshot[$Name])"
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
    $oldActive = ConvertFrom-SnapshotLine (
        'snapshot reason=log_snapshot car=new pack_car=old pack_status=ACTIVE ' +
        'decoded_bytes=123 audio_frames=100 pack_family=family'
    )
    $newActive = ConvertFrom-SnapshotLine (
        'snapshot reason=log_snapshot car=new pack_car=new pack_status=ACTIVE ' +
        'decoded_bytes=123 audio_frames=200 pack_family=family idle_rpm=900.000000 ' +
        'forward_gears=6 preview_present=true audio_errors=none underruns=0 ' +
        'startup_underruns=0 steady_underruns=0 over_range=0'
    )
    Assert-Sweep (-not (Test-TargetPublication $oldActive 'new')) (
        'old ACTIVE renderer falsely satisfied target publication'
    )
    Assert-Sweep (Test-TargetPublication $newActive 'new') 'target publication was not recognized'
    Assert-Sweep ((Get-Int64Field $newActive 'forward_gears') -eq 6) 'integer parser failed'
    Assert-Sweep ([Math]::Abs((Get-DoubleField $newActive 'idle_rpm') - 900.0) -lt 0.001) (
        'double parser failed'
    )
    Assert-Sweep (Get-BooleanField $newActive 'preview_present') 'boolean parser failed'

    $visited = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    1..$expectedCarCount | ForEach-Object {
        Assert-Sweep $visited.Add("car-$_") "synthetic ID car-$_ was duplicated"
    }
    Assert-Sweep ($visited.Count -eq $expectedCarCount) 'exact-once car coverage failed'
    Assert-Sweep (-not $visited.Add('car-1')) 'duplicate car ID was accepted'
    Write-Host 'PASS: catalog sweep parser/publication/exact-once self-test' -ForegroundColor Green
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

function Resolve-RequiredFile([string]$Path, [string]$Label) {
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    Assert-Sweep ((Test-Path -LiteralPath $resolved -PathType Leaf) -and
        (Get-Item -LiteralPath $resolved).Length -gt 0) "$Label is missing or empty: $Path"
    return $resolved
}

function Resolve-Adb {
    $localProperties = Join-Path $PSScriptRoot '..\local.properties'
    $configuredSdkRoot = $null
    $sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=(.+)$' } | Select-Object -First 1
    if ($sdkLine -and $sdkLine -match '^sdk\.dir=(.+)$') {
        $configuredSdkRoot = $Matches[1].Replace('\:', ':').Replace('\\', '\')
    }
    $candidate = @(
        (Get-Command adb -CommandType Application -ErrorAction SilentlyContinue |
            Select-Object -ExpandProperty Source -First 1),
        $(if ($configuredSdkRoot) { Join-Path $configuredSdkRoot 'platform-tools\adb.exe' }),
        $(if ($env:ANDROID_SDK_ROOT) { Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe' }),
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe' })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } |
        Select-Object -First 1
    Assert-Sweep (-not [string]::IsNullOrWhiteSpace($candidate)) 'adb was not found'
    return [string]$candidate
}

function Invoke-AdbCapture {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    $raw = @(& $script:AdbExecutable -s $script:SelectedSerial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $lines = @($raw | ForEach-Object { $_.ToString() })
    if ($exitCode -ne 0) {
        throw "CATALOG SWEEP FAILED: adb $($Arguments -join ' ') exited $exitCode. $($lines -join ' ')"
    }
    return $lines
}

function Get-DebugLines {
    return @(Invoke-AdbCapture logcat '-d' '-v' 'epoch' '-s' "$debugTag`:I" '*:S')
}

function Wait-LogResult([int]$Cursor, [string]$Command) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $escaped = [regex]::Escape($Command)
    do {
        $all = @(Get-DebugLines)
        Assert-Sweep ($all.Count -ge $Cursor) 'Debug log rotated during catalog sweep'
        $fresh = @($all | Select-Object -Skip $Cursor)
        $failure = $fresh | Where-Object { $_ -match "command=$escaped result=error(?:\s|$)" } |
            Select-Object -Last 1
        if ($failure) { throw "CATALOG SWEEP FAILED: $failure" }
        $success = $fresh | Where-Object { $_ -match "command=$escaped result=ok(?:\s|$)" } |
            Select-Object -Last 1
        if ($success) { return }
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "CATALOG SWEEP FAILED: timed out waiting for debug command '$Command'"
}

function Invoke-DriveAndWait(
    [string]$DriveCommand,
    [string]$DebugCommand,
    [hashtable]$Parameters = @{}
) {
    $cursor = @(Get-DebugLines).Count
    $arguments = @{ Command = $DriveCommand; Serial = $script:SelectedSerial }
    foreach ($entry in $Parameters.GetEnumerator()) {
        $arguments[[string]$entry.Key] = [string]$entry.Value
    }
    $null = @(& $adbDrivePath @arguments 2>&1)
    Assert-Sweep ($LASTEXITCODE -eq 0) "adb-drive '$DriveCommand' failed"
    Wait-LogResult $cursor $DebugCommand
}

function Invoke-BulkPackImportAndWait([string[]]$PackPaths) {
    Assert-Sweep ($PackPaths.Count -eq $expectedFamilyCount) (
        "Bulk import expected $expectedFamilyCount packs; found $($PackPaths.Count)"
    )
    $cursor = @(Get-DebugLines).Count
    $null = @(& $adbDrivePath -Command import-packs -PackPath $PackPaths -Serial $script:SelectedSerial 2>&1)
    Assert-Sweep ($LASTEXITCODE -eq 0) "adb-drive 'import-packs' failed"
    Wait-LogResult $cursor 'import_packs'
}

function Get-Snapshot {
    $cursor = @(Get-DebugLines).Count
    $null = @(& $adbDrivePath -Command snapshot -Serial $script:SelectedSerial 2>&1)
    Assert-Sweep ($LASTEXITCODE -eq 0) 'adb-drive snapshot failed'
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $all = @(Get-DebugLines)
        $fresh = @($all | Select-Object -Skip $cursor)
        $line = $fresh | Where-Object { $_ -match 'snapshot reason=log_snapshot(?:\s|$)' } |
            Select-Object -Last 1
        if ($line) { return ConvertFrom-SnapshotLine $line }
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw 'CATALOG SWEEP FAILED: timed out waiting for runtime snapshot'
}

function Wait-TargetCar([string]$CarId) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $snapshot = Get-Snapshot
        if ($snapshot['car'] -ceq $CarId -and $snapshot['audio_errors'] -ceq 'present') {
            throw "CATALOG SWEEP FAILED: '$CarId' published an audio error: $($snapshot['_line'])"
        }
        if (Test-TargetPublication $snapshot $CarId) {
            $framesBefore = Get-Int64Field $snapshot 'audio_frames'
            Start-Sleep -Milliseconds 250
            $confirmed = Get-Snapshot
            if ((Test-TargetPublication $confirmed $CarId) -and
                (Get-Int64Field $confirmed 'audio_frames') -gt $framesBefore) {
                return $confirmed
            }
        }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "CATALOG SWEEP FAILED: '$CarId' did not publish and advance its own ACTIVE renderer"
}

function Get-PackIdentity([string]$Path) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $entry = @($archive.Entries | Where-Object { $_.FullName -ceq 'manifest.json' })
        Assert-Sweep ($entry.Count -eq 1) "Pack '$Path' has no unique manifest.json"
        $reader = [IO.StreamReader]::new($entry[0].Open(), [Text.UTF8Encoding]::new($false, $true))
        try { $manifest = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
        $familyId = [string]$manifest.familyId
        Assert-Sweep ($familyId -cmatch '^[0-9a-f]{64}$') "Pack '$Path' has invalid familyId"
        Assert-Sweep ([int]$manifest.schemaVersion -eq 2) "Pack '$Path' is not strict schema-v2"
        $capturePlan = [string]$manifest.provenance.capturePlanSha256
        Assert-Sweep ($capturePlan -ceq $CapturePlanSha256) (
            "Pack '$Path' belongs to capture plan '$capturePlan', not '$CapturePlanSha256'"
        )
        $physicalTracks = @{}
        foreach ($track in @($manifest.tracks)) {
            $trackPath = [string]$track.path
            Assert-Sweep (-not [string]::IsNullOrWhiteSpace($trackPath)) (
                "Pack '$Path' contains a track without a physical path"
            )
            $physicalKey = "$([long]$track.frameCount)|$([int]$track.channels)|" +
                "$([int]$track.bitsPerSample)|$([int]$track.sampleRate)|$([string]$track.pcmSha256)"
            if ($physicalTracks.ContainsKey($trackPath)) {
                Assert-Sweep ($physicalTracks[$trackPath].key -ceq $physicalKey) (
                    "Pack '$Path' has inconsistent shared-path PCM metadata for '$trackPath'"
                )
            } else {
                $bytes = [long]$track.frameCount * [long]$track.channels *
                    ([long]$track.bitsPerSample / 8L)
                $physicalTracks[$trackPath] = [pscustomobject]@{ key = $physicalKey; bytes = $bytes }
            }
        }
        $uniqueDecodedBytes = [long](
            ($physicalTracks.Values | Measure-Object -Property bytes -Sum).Sum
        )
        Assert-Sweep ($uniqueDecodedBytes -gt 0) "Pack '$Path' has no decoded PCM"
        Assert-Sweep ($uniqueDecodedBytes -le $MinimumDeviceSoftBudgetBytes) (
            "Pack '$Path' requires $uniqueDecodedBytes unique decoded bytes, above " +
            "minimum-device soft budget $MinimumDeviceSoftBudgetBytes"
        )
        return [pscustomobject]@{
            familyId = $familyId
            path = $Path
            packSha256 = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
            uniqueDecodedBytes = $uniqueDecodedBytes
        }
    } finally {
        $archive.Dispose()
    }
}

$packRecords = @()
$carResults = @()
$visitedCars = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$activatedFamilies = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
try {
    $apk = Resolve-RequiredFile $ApkPath 'APK'
    $catalogFile = Resolve-RequiredFile $CatalogPath 'Catalog'
    $packsRoot = (Resolve-Path -LiteralPath $PackDirectory -ErrorAction Stop).Path
    Assert-Sweep (Test-Path -LiteralPath $packsRoot -PathType Container) (
        "PackDirectory is not a directory: $PackDirectory"
    )

    $catalog = Get-Content -LiteralPath $catalogFile -Raw | ConvertFrom-Json
    $cars = @($catalog.cars | Sort-Object id)
    Assert-Sweep ($cars.Count -eq $expectedCarCount) (
        "Catalog contains $($cars.Count) cars, expected $expectedCarCount"
    )
    $uniqueCarIds = @($cars.id | Sort-Object -Unique)
    Assert-Sweep ($uniqueCarIds.Count -eq $expectedCarCount) 'Catalog car IDs are not unique'
    foreach ($car in $cars) {
        Assert-Sweep ([string]$car.id -cmatch '^[a-z0-9_-]+$') "Invalid catalog car ID '$($car.id)'"
        Assert-Sweep ([string]$car.familyId -cmatch '^[0-9a-f]{64}$') (
            "Car '$($car.id)' has invalid familyId"
        )
        Assert-Sweep (@($car.gearbox.forwardRatios).Count -in 1..12) (
            "Car '$($car.id)' has invalid forward gear count"
        )
        Assert-Sweep ([double]$car.engine.idleRpm -gt 0.0) "Car '$($car.id)' has invalid idle RPM"
    }
    $catalogFamilyIds = @($cars.familyId | Sort-Object -Unique)
    Assert-Sweep ($catalogFamilyIds.Count -eq $expectedFamilyCount) (
        "Catalog contains $($catalogFamilyIds.Count) families, expected $expectedFamilyCount"
    )

    $candidatePacks = @(Get-ChildItem -LiteralPath $packsRoot -File -Filter '*.aclib' | Sort-Object Name)
    foreach ($pack in $candidatePacks) {
        try {
            $packRecords += Get-PackIdentity $pack.FullName
        } catch {
            # A shared directory may hold older complete plans. Ignore only an exact provenance
            # mismatch; every pack selected into this plan remains strict and fail-closed.
            if ($_.Exception.Message -notmatch 'belongs to capture plan') { throw }
        }
    }
    Assert-Sweep ($packRecords.Count -eq $expectedFamilyCount) (
        "Capture plan resolved $($packRecords.Count) packs, expected $expectedFamilyCount"
    )
    Assert-Sweep (@($packRecords.familyId | Sort-Object -Unique).Count -eq $expectedFamilyCount) (
        'Capture plan contains duplicate family packs'
    )
    $familyDifference = @(Compare-Object $catalogFamilyIds @($packRecords.familyId | Sort-Object -Unique))
    Assert-Sweep ($familyDifference.Count -eq 0) 'Pack family set differs from the catalog family set'
    $packRecords = @($packRecords | Sort-Object familyId)
    $largestPack = $packRecords | Sort-Object uniqueDecodedBytes -Descending | Select-Object -First 1

    $script:AdbExecutable = Resolve-Adb
    $env:PATH = "$(Split-Path -Parent $script:AdbExecutable);$env:PATH"
    if ([string]::IsNullOrWhiteSpace($Serial)) {
        $emulators = @(
            & $script:AdbExecutable devices |
                Where-Object { $_ -match '^(emulator-\d+)\s+device$' } |
                ForEach-Object { [regex]::Match($_, '^(emulator-\d+)').Groups[1].Value }
        )
        Assert-Sweep ($emulators.Count -eq 1) 'Specify -Serial unless one emulator is online'
        $script:SelectedSerial = $emulators[0]
    } else {
        $script:SelectedSerial = $Serial.Trim()
    }
    Assert-Sweep (((Invoke-AdbCapture shell getprop ro.kernel.qemu) -join '').Trim() -eq '1') (
        'Catalog sweep refuses non-emulator targets'
    )
    $null = Invoke-AdbCapture shell cmd media_session volume --stream 3 --set 0
    $volume = (Invoke-AdbCapture shell cmd media_session volume --stream 3 --get) -join "`n"
    Assert-Sweep ($volume -match '(?im)volume is 0 in range') 'Could not prove STREAM_MUSIC=0'

    $install = (Invoke-AdbCapture install '-r' '-d' '-t' '--bypass-low-target-sdk-block' $apk) -join "`n"
    Assert-Sweep ($install -match '(?im)^Success\s*$') 'APK installation did not report Success'
    $null = Invoke-AdbCapture shell pm grant --user 0 $packageName android.permission.POST_NOTIFICATIONS
    $null = Invoke-AdbCapture shell am force-stop $packageName
    $launch = (Invoke-AdbCapture shell am start '-W' '-n' $activityComponent) -join "`n"
    Assert-Sweep ($launch -match '(?im)^Status:\s+ok\s*$') 'Dashboard launch failed'
    $script:RuntimeMayBeRunning = $true

    Invoke-DriveAndWait 'sound' 'set_sound_enabled' @{ Value = 'false' }
    $null = Invoke-AdbCapture shell am force-stop $packageName
    Start-Sleep -Milliseconds 300
    $launch = (Invoke-AdbCapture shell am start '-W' '-n' $activityComponent) -join "`n"
    Assert-Sweep ($launch -match '(?im)^Status:\s+ok\s*$') 'Muted dashboard relaunch failed'
    $muted = Get-Snapshot
    Assert-Sweep ($muted['sound_enabled'] -ceq 'false') 'Persisted startup mute was not restored'
    Assert-Sweep ($muted['audio_status'] -ceq 'OFFLINE') 'Startup mute opened AudioTrack'
    Assert-Sweep ((Get-Int64Field $muted 'decoded_bytes') -eq 0) 'Startup mute decoded a family'
    Assert-Sweep ((Get-Int64Field $muted 'sample_rate') -eq 0) 'Startup mute configured output'

    Write-Host "Importing exact catalog and $expectedFamilyCount strict private packs"
    Invoke-DriveAndWait 'import-catalog' 'import_catalog' @{ Path = $catalogFile }
    Invoke-BulkPackImportAndWait @($packRecords.path)
    Invoke-DriveAndWait 'sound' 'set_sound_enabled' @{ Value = 'true' }
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME

    $ordinal = 0
    foreach ($car in $cars) {
        $ordinal += 1
        $carId = [string]$car.id
        Assert-Sweep $visitedCars.Add($carId) "Car '$carId' was selected more than once"
        Invoke-DriveAndWait 'car' 'select_car' @{ CarId = $carId }
        $snapshot = Wait-TargetCar $carId

        $expectedFamily = [string]$car.familyId
        $expectedGears = @($car.gearbox.forwardRatios).Count
        $expectedIdle = [double]$car.engine.idleRpm
        $expectedPreview = -not [string]::IsNullOrWhiteSpace([string]$car.previewPath)
        $actualFamily = [string]$snapshot['pack_family']
        $actualGears = Get-Int64Field $snapshot 'forward_gears'
        $actualIdle = Get-DoubleField $snapshot 'idle_rpm'
        $actualPreview = Get-BooleanField $snapshot 'preview_present'

        Assert-Sweep ($actualFamily -ceq $expectedFamily) (
            "Car '$carId' activated family '$actualFamily', expected '$expectedFamily'"
        )
        Assert-Sweep ($actualGears -eq $expectedGears) (
            "Car '$carId' published $actualGears gears, expected $expectedGears"
        )
        Assert-Sweep ([Math]::Abs($actualIdle - $expectedIdle) -le 0.001) (
            "Car '$carId' published idle $actualIdle, expected $expectedIdle"
        )
        Assert-Sweep ($actualPreview -eq $expectedPreview) (
            "Car '$carId' preview presence=$actualPreview, expected $expectedPreview"
        )
        Assert-Sweep ($snapshot['audio_errors'] -ceq 'none') "Car '$carId' has an audio error"
        Assert-Sweep ((Get-Int64Field $snapshot 'underruns') -eq 0) "Car '$carId' has underruns"
        Assert-Sweep ((Get-Int64Field $snapshot 'startup_underruns') -eq 0) (
            "Car '$carId' has startup underruns"
        )
        Assert-Sweep ((Get-Int64Field $snapshot 'steady_underruns') -eq 0) (
            "Car '$carId' has steady underruns"
        )
        Assert-Sweep ((Get-Int64Field $snapshot 'over_range') -eq 0) (
            "Car '$carId' produced over-range samples"
        )
        $null = $activatedFamilies.Add($actualFamily)
        $carResults += [pscustomobject][ordered]@{
            ordinal = $ordinal
            carId = $carId
            familyId = $actualFamily
            decodedBytes = Get-Int64Field $snapshot 'decoded_bytes'
            idleRpm = $actualIdle
            forwardGears = $actualGears
            previewPresent = $actualPreview
            audioFrames = Get-Int64Field $snapshot 'audio_frames'
            underruns = Get-Int64Field $snapshot 'underruns'
            overRangeSamples = Get-Int64Field $snapshot 'over_range'
        }
        if ($ordinal % 10 -eq 0 -or $ordinal -eq $expectedCarCount) {
            Write-Host "  selected $ordinal/$expectedCarCount; families $($activatedFamilies.Count)/$expectedFamilyCount"
        }
    }

    Assert-Sweep ($visitedCars.Count -eq $expectedCarCount) (
        "Visited $($visitedCars.Count)/$expectedCarCount unique cars"
    )
    Assert-Sweep ($activatedFamilies.Count -eq $expectedFamilyCount) (
        "Activated $($activatedFamilies.Count)/$expectedFamilyCount unique families"
    )
    Assert-Sweep ($carResults.Count -eq $expectedCarCount) 'Result count differs from exact car count'

    if ([string]::IsNullOrWhiteSpace($OutputPath)) {
        $reportDirectory = Join-Path (Split-Path -Parent $PSScriptRoot) 'build\reports\emulator'
        $null = New-Item -ItemType Directory -Force -Path $reportDirectory
        $OutputPath = Join-Path $reportDirectory (
            'catalog-sweep-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '.json'
        )
    }
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    $outputParent = Split-Path -Parent $resolvedOutput
    if ($outputParent) { $null = New-Item -ItemType Directory -Force -Path $outputParent }
    $report = [ordered]@{
        schema = 'byd-emulator-ac-catalog-sweep-v1'
        createdAtUtc = [DateTime]::UtcNow.ToString('o')
        emulator = $script:SelectedSerial
        apkSha256 = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
        catalogSha256 = (Get-FileHash -LiteralPath $catalogFile -Algorithm SHA256).Hash.ToLowerInvariant()
        declaredCatalogSha256 = [string]$catalog.catalogSha256
        capturePlanSha256 = $CapturePlanSha256
        carCount = $cars.Count
        visitedCarCount = $visitedCars.Count
        familyCount = $catalogFamilyIds.Count
        activatedFamilyCount = $activatedFamilies.Count
        activatedFamilyIds = @($activatedFamilies | Sort-Object)
        minimumDeviceSoftBudgetBytes = $MinimumDeviceSoftBudgetBytes
        maximumUniqueDecodedFamilyId = [string]$largestPack.familyId
        maximumUniqueDecodedBytes = [long]$largestPack.uniqueDecodedBytes
        packs = @($packRecords | ForEach-Object {
            [ordered]@{
                familyId = $_.familyId
                packSha256 = $_.packSha256
                uniqueDecodedBytes = $_.uniqueDecodedBytes
            }
        })
        cars = $carResults
    }
    [IO.File]::WriteAllText(
        $resolvedOutput,
        ($report | ConvertTo-Json -Depth 8),
        [Text.UTF8Encoding]::new($false)
    )
    Write-Host 'PASS: exact official catalog sweep completed' -ForegroundColor Green
    Write-Host "  cars: $($visitedCars.Count)/$expectedCarCount exactly once"
    Write-Host "  families: $($activatedFamilies.Count)/$expectedFamilyCount activated"
    Write-Host (
        "  max unique decoded PCM: $($largestPack.uniqueDecodedBytes)/" +
            "$MinimumDeviceSoftBudgetBytes bytes ($($largestPack.familyId))"
    )
    Write-Host "  report: $resolvedOutput"
    Write-Host "  report SHA-256: $((Get-FileHash $resolvedOutput -Algorithm SHA256).Hash.ToLowerInvariant())"
} finally {
    if ($script:RuntimeMayBeRunning -and $script:AdbExecutable -and $script:SelectedSerial) {
        try {
            $null = @(& $adbDrivePath -Command stop -Serial $script:SelectedSerial 2>&1)
            Start-Sleep -Seconds 3
            $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
        } catch {
            Write-Warning "Could not stop the catalog-sweep runtime: $($_.Exception.Message)"
            try { $null = Invoke-AdbCapture shell am force-stop $packageName } catch { }
        }
    }
    $env:PATH = $script:OriginalPath
}
