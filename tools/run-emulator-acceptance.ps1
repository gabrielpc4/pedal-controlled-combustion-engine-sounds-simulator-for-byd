<#
.SYNOPSIS
Runs the headless muted-emulator lifecycle acceptance flow through ADB only.

.DESCRIPTION
Installs a debug APK, imports one private catalog and one private .aclib through adb-drive.ps1,
proves a corrupt replacement cannot modify the installed family, drives the simulator, verifies
Home/return continuity from BYDDriveDebug counters, and invokes the exact Stop action in the
foreground notification. It then proves sticky suppression and an explicit fresh-session reopen.
The script never clears logcat, sends host input, or enables audible output.

.EXAMPLE
.\tools\run-emulator-acceptance.ps1 `
  -ApkPath .\mobile\build\outputs\apk\debug\engine-sounds-simulator-build-123-debug.apk `
  -CatalogPath C:\private\catalog-v1.json `
  -PackPath C:\private\tatuus.aclib `
  -CarId tatuusfa1 `
  -Serial emulator-5554

.EXAMPLE
.\tools\run-emulator-acceptance.ps1 -SelfTest
#>
[CmdletBinding(DefaultParameterSetName = "Run")]
param(
    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$ApkPath,

    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$CatalogPath,

    [Parameter(Mandatory = $true, ParameterSetName = "Run")]
    [string]$PackPath,

    [Parameter(ParameterSetName = "Run")]
    [ValidateNotNullOrEmpty()]
    [string]$CarId = "tatuusfa1",

    [Parameter(ParameterSetName = "Run")]
    [string]$Serial,

    [Parameter(ParameterSetName = "Run")]
    [switch]$SkipInstall,

    [Parameter(ParameterSetName = "Run")]
    [ValidateRange(15, 600)]
    [int]$TimeoutSeconds = 120,

    [Parameter(Mandatory = $true, ParameterSetName = "SelfTest")]
    [switch]$SelfTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$packageName = "com.gabrielpc.enginesoundsimulator"
$appDisplayName = "Engine Sounds Simulator"
$activityComponent = "$packageName/.MainActivity"
$serviceComponent = "$packageName/.drive.DriveRuntimeService"
$notificationId = 10041
$debugLogTag = "BYDDriveDebug"
$adbDrivePath = Join-Path $PSScriptRoot "adb-drive.ps1"
$script:AdbExecutable = $null
$script:SelectedSerial = $null
$script:OriginalPath = $env:PATH
$script:RuntimeMayBeRunning = $false
$script:StopVerified = $false

function Assert-Acceptance {
    param(
        [Parameter(Mandatory = $true)][bool]$Condition,
        [Parameter(Mandatory = $true)][string]$Message
    )
    if (-not $Condition) {
        throw "ACCEPTANCE FAILED: $Message"
    }
}

function ConvertFrom-DebugSnapshotLine {
    param([Parameter(Mandatory = $true)][string]$Line)

    Assert-Acceptance ($Line -match "snapshot reason=") "Expected a BYDDriveDebug snapshot line; got: $Line"
    $fields = @{}
    foreach ($match in [regex]::Matches($Line, "(?<key>[a-z_]+)=(?<value>\S+)")) {
        $fields[$match.Groups["key"].Value] = $match.Groups["value"].Value
    }
    $fields["_line"] = $Line
    return $fields
}

function Get-RequiredSnapshotField {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Snapshot,
        [Parameter(Mandatory = $true)][string]$Name
    )
    Assert-Acceptance ($Snapshot.ContainsKey($Name)) (
        "Debug snapshot is missing '$Name'. Install a debug APK exposing monotonic core_steps " +
        "and audio_frames counters. Snapshot: $($Snapshot['_line'])"
    )
    return [string]$Snapshot[$Name]
}

function Get-SnapshotInt64 {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Snapshot,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $value = Get-RequiredSnapshotField -Snapshot $Snapshot -Name $Name
    $parsed = 0L
    Assert-Acceptance (
        [long]::TryParse(
            $value,
            [Globalization.NumberStyles]::Integer,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )
    ) "Snapshot field '$Name' is not an integer: '$value'"
    return $parsed
}

function Get-SnapshotDouble {
    param(
        [Parameter(Mandatory = $true)][hashtable]$Snapshot,
        [Parameter(Mandatory = $true)][string]$Name
    )
    $value = Get-RequiredSnapshotField -Snapshot $Snapshot -Name $Name
    $parsed = 0.0
    Assert-Acceptance (
        [double]::TryParse(
            $value,
            [Globalization.NumberStyles]::Float,
            [Globalization.CultureInfo]::InvariantCulture,
            [ref]$parsed
        )
    ) "Snapshot field '$Name' is not numeric: '$value'"
    return $parsed
}

function Assert-CounterAdvanced {
    param(
        [Parameter(Mandatory = $true)][long]$Before,
        [Parameter(Mandatory = $true)][long]$After,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Transition
    )
    Assert-Acceptance ($After -gt $Before) (
        "$Name did not advance across $Transition (before=$Before, after=$After). " +
        "A reset or stalled runtime is not accepted."
    )
}

function ConvertFrom-UiHierarchyOutput {
    param([Parameter(Mandatory = $true)][string]$Output)

    $xmlMatch = [regex]::Match($Output, '(?s)<\?xml.+')
    Assert-Acceptance $xmlMatch.Success "Could not find XML in Android's UI hierarchy output"
    try {
        return [xml]$xmlMatch.Value
    } catch {
        throw "ACCEPTANCE FAILED: Android UI hierarchy was invalid XML: $($_.Exception.Message)"
    }
}

function Get-DriveNotificationRow {
    param(
        [Parameter(Mandatory = $true)][xml]$Hierarchy,
        [Parameter(Mandatory = $true)][string]$ExpectedTitle,
        [switch]$AllowMissing
    )

    $rows = @($Hierarchy.SelectNodes(
        "//node[@resource-id='com.android.systemui:id/expandableNotificationRow']"
    ))
    $matching = @(
        $rows | Where-Object {
            $row = $_
            @($row.SelectNodes(".//node[@resource-id='android:id/title']")) |
                Where-Object { $_.GetAttribute("text") -ceq $ExpectedTitle }
        }
    )
    if ($AllowMissing -and $matching.Count -eq 0) { return $null }
    Assert-Acceptance ($matching.Count -eq 1) (
        "Expected exactly one notification row titled '$ExpectedTitle'; found $($matching.Count)"
    )
    return $matching[0]
}

function Get-NodeTapCoordinates {
    param(
        [Parameter(Mandatory = $true)][System.Xml.XmlElement]$Node,
        [Parameter(Mandatory = $true)][string]$Description
    )

    Assert-Acceptance ($Node.GetAttribute("clickable") -eq "true") "$Description is not clickable"
    Assert-Acceptance ($Node.GetAttribute("enabled") -eq "true") "$Description is not enabled"
    $bounds = $Node.GetAttribute("bounds")
    $match = [regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    Assert-Acceptance $match.Success "$Description has invalid bounds '$bounds'"
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    Assert-Acceptance ($right -gt $left -and $bottom -gt $top) "$Description has empty bounds '$bounds'"
    return @(
        [int][Math]::Floor(($left + $right) / 2.0),
        [int][Math]::Floor(($top + $bottom) / 2.0)
    )
}

function Get-Sha256Hex {
    param([Parameter(Mandatory = $true)][byte[]]$Bytes)

    $algorithm = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($algorithm.ComputeHash($Bytes))).Replace("-", "")
    } finally {
        $algorithm.Dispose()
    }
}

function Get-UInt16LittleEndian {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [Parameter(Mandatory = $true)][int]$Offset
    )
    return [int]$Bytes[$Offset] -bor ([int]$Bytes[$Offset + 1] -shl 8)
}

function Get-UInt32LittleEndian {
    param(
        [Parameter(Mandatory = $true)][byte[]]$Bytes,
        [Parameter(Mandatory = $true)][int]$Offset
    )
    return [uint64]$Bytes[$Offset] -bor
        ([uint64]$Bytes[$Offset + 1] -shl 8) -bor
        ([uint64]$Bytes[$Offset + 2] -shl 16) -bor
        ([uint64]$Bytes[$Offset + 3] -shl 24)
}

function New-CorruptAclibFixture {
    param(
        [Parameter(Mandatory = $true)][string]$SourcePath,
        [Parameter(Mandatory = $true)][string]$TargetMember,
        [Parameter(Mandatory = $true)][string]$DestinationPath
    )

    # Mutate one byte in a STORED member's local-file payload. The central-directory CRC and the
    # compiler hashes remain unchanged, so the archive is structurally recognizable but must fail
    # closed during extraction/integrity validation. The source pack is never opened for writing.
    $bytes = [IO.File]::ReadAllBytes($SourcePath)
    $offset = 0
    $mutated = $false
    while ($offset + 4 -le $bytes.Length) {
        $signature = Get-UInt32LittleEndian -Bytes $bytes -Offset $offset
        if ($signature -eq 0x02014b50L -or $signature -eq 0x06054b50L) { break }
        Assert-Acceptance ($signature -eq 0x04034b50L) (
            "Unexpected ZIP record 0x$($signature.ToString('x8')) at byte $offset"
        )
        Assert-Acceptance ($offset + 30 -le $bytes.Length) "Truncated ZIP local-file header"
        $flags = Get-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 6)
        $method = Get-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 8)
        $compressedSize = Get-UInt32LittleEndian -Bytes $bytes -Offset ($offset + 18)
        $nameLength = Get-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 26)
        $extraLength = Get-UInt16LittleEndian -Bytes $bytes -Offset ($offset + 28)
        Assert-Acceptance (($flags -band 0x08) -eq 0) (
            "Pack member uses a data descriptor; deterministic STORED packs must declare sizes in local headers"
        )
        $nameStart = $offset + 30
        $dataStart = $nameStart + $nameLength + $extraLength
        $dataEnd = [uint64]$dataStart + $compressedSize
        Assert-Acceptance ($dataEnd -le $bytes.Length) "ZIP member extends past the archive"
        $entryName = [Text.Encoding]::UTF8.GetString($bytes, $nameStart, $nameLength)
        if ($entryName -ceq $TargetMember) {
            Assert-Acceptance ($method -eq 0) "Target pack member '$TargetMember' is not STORED"
            Assert-Acceptance ($compressedSize -gt 0) "Target pack member '$TargetMember' is empty"
            $mutationOffset = $dataStart + [int][Math]::Floor($compressedSize / 2.0)
            $bytes[$mutationOffset] = $bytes[$mutationOffset] -bxor 0x01
            $mutated = $true
            break
        }
        $offset = [int]$dataEnd
    }
    Assert-Acceptance $mutated "Could not locate '$TargetMember' in the pack's local ZIP records"
    [IO.File]::WriteAllBytes($DestinationPath, $bytes)
    Assert-Acceptance ((Get-Item -LiteralPath $DestinationPath).Length -eq $bytes.Length) (
        "Corrupt fixture length differs from the source pack"
    )
}

function Remove-SafeTemporaryDirectory {
    param([Parameter(Mandatory = $true)][string]$Path)

    if (-not (Test-Path -LiteralPath $Path)) { return }
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd(
        [IO.Path]::DirectorySeparatorChar,
        [IO.Path]::AltDirectorySeparatorChar
    )
    $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path).Path)
    $expectedPrefix = $temporaryRoot + [IO.Path]::DirectorySeparatorChar + "byd-aclib-acceptance-"
    Assert-Acceptance ($resolved.StartsWith($expectedPrefix, [StringComparison]::OrdinalIgnoreCase)) (
        "Refusing to recursively remove non-acceptance path '$resolved'"
    )
    Remove-Item -LiteralPath $resolved -Recurse -Force
}

function Invoke-HarnessSelfTest {
    $line = "1700000000.000 I/BYDDriveDebug: snapshot reason=log_snapshot ui_visible=false " +
        "runtime_started=true car=tatuusfa1 mode=SIMULATOR transmission=DRIVE throttle=0.000 " +
        "brake=0.000 pack_status=ACTIVE decoded_bytes=4096 core_steps=2400 audio_frames=576000"
    $snapshot = ConvertFrom-DebugSnapshotLine -Line $line
    Assert-Acceptance ($snapshot["car"] -eq "tatuusfa1") "Snapshot parser lost the car id"
    Assert-Acceptance ((Get-SnapshotInt64 $snapshot "core_steps") -eq 2400L) "core_steps parser failed"
    Assert-Acceptance ((Get-SnapshotInt64 $snapshot "audio_frames") -eq 576000L) "audio_frames parser failed"
    Assert-CounterAdvanced -Before 100L -After 101L -Name "mock" -Transition "self-test"

    $rejected = $false
    try {
        Assert-CounterAdvanced -Before 100L -After 100L -Name "mock" -Transition "self-test"
    } catch {
        $rejected = $_.Exception.Message -like "ACCEPTANCE FAILED:*"
    }
    Assert-Acceptance $rejected "Non-advancing counter assertion did not fail closed"

    [xml]$mockHierarchy = @'
<?xml version="1.0" encoding="UTF-8"?>
<hierarchy>
  <node resource-id="com.android.systemui:id/expandableNotificationRow" text="" clickable="true" enabled="true" bounds="[0,0][100,100]">
    <node resource-id="android:id/title" text="Unrelated" clickable="false" enabled="true" bounds="[0,0][50,20]" />
    <node resource-id="android:id/action0" text="Stop" content-desc="Stop" clickable="true" enabled="true" bounds="[0,20][50,40]" />
  </node>
  <node resource-id="com.android.systemui:id/expandableNotificationRow" text="" clickable="true" enabled="true" bounds="[0,100][100,200]">
    <node resource-id="android:id/app_name_text" text="Engine Sounds Simulator" clickable="false" enabled="true" bounds="[0,100][80,120]" />
    <node resource-id="android:id/title" text="Tatuus FA01" clickable="false" enabled="true" bounds="[0,120][80,140]" />
    <node resource-id="android:id/action0" text="Stop" content-desc="Stop" clickable="true" enabled="true" bounds="[20,150][60,190]" />
  </node>
</hierarchy>
'@
    $mockRow = Get-DriveNotificationRow -Hierarchy $mockHierarchy -ExpectedTitle "Tatuus FA01"
    $mockStop = @(
        @($mockRow.SelectNodes(".//node[@resource-id='android:id/action0']")) |
            Where-Object { $_.GetAttribute("text") -ceq "Stop" }
    )
    Assert-Acceptance ($mockStop.Count -eq 1) "Scoped notification Stop lookup failed"
    $coordinates = @(Get-NodeTapCoordinates -Node $mockStop[0] -Description "mock Stop")
    Assert-Acceptance ($coordinates[0] -eq 40 -and $coordinates[1] -eq 170) (
        "Notification action center calculation failed"
    )

    $sha = Get-Sha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes("atomic"))
    Assert-Acceptance ($sha -eq "FD43322A4DA6F0E914C9624D6248D376A1518A9964349ED64679D316D7AF8290") (
        "SHA-256 helper self-test failed"
    )

    Assert-Acceptance ([BitConverter]::IsLittleEndian) "Harness requires a little-endian Windows host"
    $fixtureDirectory = Join-Path `
        ([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) `
        ("byd-aclib-acceptance-" + [Guid]::NewGuid().ToString("N"))
    try {
        $null = New-Item -ItemType Directory -Path $fixtureDirectory
        $sourceFixture = Join-Path $fixtureDirectory "stored-source.zip"
        $corruptFixture = Join-Path $fixtureDirectory "stored-corrupt.zip"
        $memberName = "audio/test.flac"
        $nameBytes = [Text.Encoding]::UTF8.GetBytes($memberName)
        $payload = [Text.Encoding]::ASCII.GetBytes("fLaCmock-payload")
        $mockZip = [byte[]]::new(30 + $nameBytes.Length + $payload.Length + 4)
        [Array]::Copy([BitConverter]::GetBytes([uint32]0x04034b50), 0, $mockZip, 0, 4)
        [Array]::Copy([BitConverter]::GetBytes([uint32]$payload.Length), 0, $mockZip, 18, 4)
        [Array]::Copy([BitConverter]::GetBytes([uint32]$payload.Length), 0, $mockZip, 22, 4)
        [Array]::Copy([BitConverter]::GetBytes([uint16]$nameBytes.Length), 0, $mockZip, 26, 2)
        [Array]::Copy($nameBytes, 0, $mockZip, 30, $nameBytes.Length)
        [Array]::Copy($payload, 0, $mockZip, 30 + $nameBytes.Length, $payload.Length)
        [Array]::Copy(
            [BitConverter]::GetBytes([uint32]0x02014b50),
            0,
            $mockZip,
            30 + $nameBytes.Length + $payload.Length,
            4
        )
        [IO.File]::WriteAllBytes($sourceFixture, $mockZip)
        $sourceHash = (Get-FileHash -LiteralPath $sourceFixture -Algorithm SHA256).Hash
        New-CorruptAclibFixture `
            -SourcePath $sourceFixture `
            -TargetMember $memberName `
            -DestinationPath $corruptFixture
        $corruptBytes = [IO.File]::ReadAllBytes($corruptFixture)
        $differences = 0
        for ($index = 0; $index -lt $mockZip.Length; $index++) {
            if ($mockZip[$index] -ne $corruptBytes[$index]) { $differences += 1 }
        }
        Assert-Acceptance ($differences -eq 1) "Corrupt fixture must flip exactly one member byte"
        Assert-Acceptance (
            (Get-FileHash -LiteralPath $sourceFixture -Algorithm SHA256).Hash -ceq $sourceHash
        ) "Corrupt fixture helper modified its source"
    } finally {
        Remove-SafeTemporaryDirectory -Path $fixtureDirectory
    }
    Write-Host "PASS: emulator acceptance harness parser/assertion self-test (no ADB used)" -ForegroundColor Green
}

if ($SelfTest) {
    Invoke-HarnessSelfTest
    exit 0
}

function Resolve-RequiredFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    Assert-Acceptance (Test-Path -LiteralPath $resolved -PathType Leaf) "$Label is not a file: $Path"
    Assert-Acceptance ((Get-Item -LiteralPath $resolved).Length -gt 0L) "$Label is empty: $resolved"
    return $resolved
}

function Get-AclibDescriptor {
    param([Parameter(Mandatory = $true)][string]$Path)

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $manifestEntries = @($archive.Entries | Where-Object { $_.FullName -ceq "manifest.json" })
        Assert-Acceptance ($manifestEntries.Count -eq 1) "Pack must contain one root manifest.json"
        $reader = [IO.StreamReader]::new(
            $manifestEntries[0].Open(),
            [Text.UTF8Encoding]::new($false, $true),
            $true
        )
        try {
            $manifest = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    $familyId = [string]$manifest.familyId
    Assert-Acceptance ($familyId -cmatch '^[0-9a-f]{64}$') "Pack manifest has an invalid familyId"
    $tracks = @($manifest.tracks)
    Assert-Acceptance ($tracks.Count -gt 0) "Acceptance pack has no audio tracks to corrupt"
    $targetMember = [string]$tracks[0].path
    $assets = @($manifest.assets)
    $members = @(".ready-v1", "manifest.json") +
        @($tracks | ForEach-Object { [string]$_.path }) +
        @($assets | ForEach-Object { [string]$_.path })
    Assert-Acceptance (($members | Select-Object -Unique).Count -eq $members.Count) (
        "Pack manifest contains duplicate installed member paths"
    )
    foreach ($member in $members) {
        Assert-Acceptance ($member -cmatch '^[A-Za-z0-9._/-]+$') (
            "Acceptance fingerprint cannot safely address pack member '$member' through adb"
        )
    }
    return [pscustomobject]@{
        FamilyId = $familyId
        TargetMember = $targetMember
        InstalledMembers = @($members | Sort-Object)
    }
}

function Get-CatalogCarDisplayName {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$ExpectedCarId
    )

    $catalogObject = Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
    $matches = @($catalogObject.cars | Where-Object { [string]$_.id -ceq $ExpectedCarId })
    Assert-Acceptance ($matches.Count -eq 1) (
        "Catalog must contain exactly one car '$ExpectedCarId'; found $($matches.Count)"
    )
    $displayName = [string]$matches[0].name
    Assert-Acceptance (-not [string]::IsNullOrWhiteSpace($displayName)) (
        "Catalog car '$ExpectedCarId' has no display name"
    )
    return $displayName
}

function Invoke-AdbCapture {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = @(& $script:AdbExecutable -s $script:SelectedSerial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    $text = @($output | ForEach-Object { $_.ToString() })
    if ($null -ne $exitCode -and $exitCode -ne 0) {
        throw "ACCEPTANCE FAILED: adb $($Arguments -join ' ') exited $exitCode. $($text -join ' ')"
    }
    return $text
}

function Get-InstalledFamilyFingerprint {
    param(
        [Parameter(Mandatory = $true)][string]$FamilyId,
        [Parameter(Mandatory = $true)][string[]]$ExpectedMembers
    )

    Assert-Acceptance ($FamilyId -cmatch '^[0-9a-f]{64}$') "Unsafe family id '$FamilyId'"
    $remoteRoot = "files/assetto_sound_library_v1/installed/$FamilyId"
    $listed = @(
        Invoke-AdbCapture shell run-as $packageName find $remoteRoot "-type" f |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    $actualMembers = @(
        $listed | ForEach-Object {
            $prefix = "$remoteRoot/"
            Assert-Acceptance ($_.StartsWith($prefix, [StringComparison]::Ordinal)) (
                "Installed-family listing escaped '$remoteRoot': $_"
            )
            $_.Substring($prefix.Length)
        } | Sort-Object
    )
    $expectedSorted = @($ExpectedMembers | Sort-Object)
    $memberDifference = @(
        Compare-Object -ReferenceObject $expectedSorted -DifferenceObject $actualMembers -CaseSensitive
    )
    Assert-Acceptance ($memberDifference.Count -eq 0) (
        "Installed family member set differs from the pack manifest: $($memberDifference -join '; ')"
    )

    $lines = foreach ($relative in $actualMembers) {
        $remoteFile = "$remoteRoot/$relative"
        $hashOutput = (Invoke-AdbCapture shell run-as $packageName sha256sum $remoteFile) -join "`n"
        $hashMatch = [regex]::Match($hashOutput.Trim(), '^([0-9a-fA-F]{64})(?:\s|$)')
        Assert-Acceptance $hashMatch.Success "Could not hash installed member '$relative': $hashOutput"
        $sizeText = ((Invoke-AdbCapture shell run-as $packageName stat "-c" "%s" $remoteFile) -join "").Trim()
        $size = 0L
        Assert-Acceptance ([long]::TryParse($sizeText, [ref]$size) -and $size -ge 0L) (
            "Could not read installed member length for '$relative': '$sizeText'"
        )
        "$relative`t$size`t$($hashMatch.Groups[1].Value.ToUpperInvariant())"
    }
    $canonical = $lines -join "`n"
    return [pscustomobject]@{
        Lines = @($lines)
        AggregateSha256 = Get-Sha256Hex -Bytes ([Text.Encoding]::UTF8.GetBytes($canonical))
    }
}

function Assert-NoPackTransactionDebris {
    $installedRoot = "files/assetto_sound_library_v1/installed"
    $installedNames = @(
        Invoke-AdbCapture shell run-as $packageName ls "-1a" $installedRoot |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    $debris = @($installedNames | Where-Object { $_ -like ".staging-*" -or $_ -like ".backup-*" })
    Assert-Acceptance ($debris.Count -eq 0) (
        "Pack import left transaction debris under installed/: $($debris -join ', ')"
    )
    $incoming = @(
        Invoke-AdbCapture shell run-as $packageName find "files/assetto_sound_library_v1/incoming" "-type" f |
            ForEach-Object { $_.Trim() } |
            Where-Object { $_ }
    )
    Assert-Acceptance ($incoming.Count -eq 0) (
        "Pack import left incoming partial files: $($incoming -join ', ')"
    )
}

function Invoke-DriveCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [string[]]$DriveArguments = @()
    )

    $invokeParams = @{ Serial = $script:SelectedSerial }
    for ($index = 0; $index -lt $DriveArguments.Count; $index += 2) {
        $name = $DriveArguments[$index]
        Assert-Acceptance ($name.StartsWith("-") -and $index + 1 -lt $DriveArguments.Count) (
            "Invalid adb-drive argument list for '$Command': $($DriveArguments -join ' ')"
        )
        $invokeParams[$name.TrimStart("-")] = $DriveArguments[$index + 1]
    }
    $output = @(& $adbDrivePath -Command $Command @invokeParams 2>&1)
    if ($null -ne $LASTEXITCODE -and $LASTEXITCODE -ne 0) {
        throw "ACCEPTANCE FAILED: adb-drive '$Command' exited $LASTEXITCODE. $($output -join ' ')"
    }
    return @($output | ForEach-Object { $_.ToString() })
}

function Get-DebugLogLines {
    return @(
        Invoke-AdbCapture logcat "-d" "-v" epoch "-s" "$debugLogTag`:I" "*:S"
    )
}

function Get-LogCursor {
    return @(Get-DebugLogLines).Count
}

function Get-FreshLogLines {
    param([Parameter(Mandatory = $true)][int]$Cursor)

    $all = @(Get-DebugLogLines)
    Assert-Acceptance ($all.Count -ge $Cursor) (
        "The BYDDriveDebug log buffer rotated during the run. Rerun with a larger device log buffer; " +
        "the harness will not clear logcat or risk matching stale evidence."
    )
    if ($all.Count -eq $Cursor) { return @() }
    return @($all | Select-Object -Skip $Cursor)
}

function Wait-FreshLogMatch {
    param(
        [Parameter(Mandatory = $true)][int]$Cursor,
        [Parameter(Mandatory = $true)][string]$SuccessPattern,
        [string]$FailurePattern,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $fresh = @(Get-FreshLogLines -Cursor $Cursor)
        if ($FailurePattern) {
            $failure = $fresh | Where-Object { $_ -match $FailurePattern } | Select-Object -Last 1
            if ($null -ne $failure) {
                throw "ACCEPTANCE FAILED: $Description reported an error: $failure"
            }
        }
        $success = $fresh | Where-Object { $_ -match $SuccessPattern } | Select-Object -Last 1
        if ($null -ne $success) { return [string]$success }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)

    throw "ACCEPTANCE FAILED: Timed out after ${TimeoutSeconds}s waiting for $Description."
}

function Invoke-DriveAndWait {
    param(
        [Parameter(Mandatory = $true)][string]$DriveCommand,
        [Parameter(Mandatory = $true)][string]$DebugCommand,
        [string[]]$DriveArguments = @()
    )

    $cursor = Get-LogCursor
    $null = Invoke-DriveCommand -Command $DriveCommand -DriveArguments $DriveArguments
    $escaped = [regex]::Escape($DebugCommand)
    return Wait-FreshLogMatch `
        -Cursor $cursor `
        -SuccessPattern "command=$escaped result=ok(?:\s|$)" `
        -FailurePattern "command=$escaped result=error(?:\s|$)" `
        -Description "debug command '$DebugCommand'"
}

function Invoke-DriveAndExpectError {
    param(
        [Parameter(Mandatory = $true)][string]$DriveCommand,
        [Parameter(Mandatory = $true)][string]$DebugCommand,
        [string[]]$DriveArguments = @()
    )

    $cursor = Get-LogCursor
    $null = Invoke-DriveCommand -Command $DriveCommand -DriveArguments $DriveArguments
    $escaped = [regex]::Escape($DebugCommand)
    return Wait-FreshLogMatch `
        -Cursor $cursor `
        -SuccessPattern "command=$escaped result=error(?:\s|$)" `
        -Description "expected rejection from debug command '$DebugCommand'"
}

function Get-LiveSnapshot {
    $cursor = Get-LogCursor
    $null = Invoke-DriveCommand -Command "snapshot"
    $line = Wait-FreshLogMatch `
        -Cursor $cursor `
        -SuccessPattern "snapshot reason=log_snapshot(?:\s|$)" `
        -FailurePattern "command=log_snapshot result=error(?:\s|$)" `
        -Description "a fresh runtime snapshot"
    return ConvertFrom-DebugSnapshotLine -Line $line
}

function Wait-SnapshotCondition {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Predicate,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $watch = [Diagnostics.Stopwatch]::StartNew()
    $last = $null
    do {
        $last = Get-LiveSnapshot
        if (& $Predicate $last) { return $last }
        if ($last.ContainsKey("pack_status") -and $last["pack_status"] -eq "ERROR") {
            throw "ACCEPTANCE FAILED: Pack activation entered ERROR while waiting for $Description. $($last['_line'])"
        }
        Start-Sleep -Milliseconds 500
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)

    $lastLine = if ($null -ne $last) { $last["_line"] } else { "no snapshot returned" }
    throw "ACCEPTANCE FAILED: Timed out after ${TimeoutSeconds}s waiting for $Description. Last: $lastLine"
}

function Test-ServiceRunning {
    $services = (Invoke-AdbCapture shell dumpsys activity services $packageName) -join "`n"
    return $services -match [regex]::Escape($serviceComponent)
}

function Test-DriveNotificationPresent {
    $dump = (Invoke-AdbCapture shell dumpsys notification "--noredact") -join "`n"
    $packagePattern = [regex]::Escape($packageName)
    return $dump -match "(?m)^.*NotificationRecord.*pkg=$packagePattern.*\bid=$notificationId(?:\s|\b)"
}

function Wait-DriveNotificationState {
    param(
        [Parameter(Mandatory = $true)][bool]$Present,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Test-DriveNotificationPresent) -eq $Present) { return }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "ACCEPTANCE FAILED: Timed out after ${TimeoutSeconds}s waiting for $Description."
}

function Read-AndroidUiHierarchy {
    # Recent API 36 uiautomator builds print only "dumped to /dev/tty" instead of the XML bytes.
    # Use an exact, randomized shell-owned temp file and always remove it after reading.
    $remotePath = "/data/local/tmp/byd-acceptance-ui-$PID-$([Guid]::NewGuid().ToString('N')).xml"
    try {
        $dumpResult = (Invoke-AdbCapture shell uiautomator dump $remotePath) -join "`n"
        Assert-Acceptance ($dumpResult -match [regex]::Escape($remotePath)) (
            "uiautomator did not confirm its hierarchy target: $dumpResult"
        )
        $output = (Invoke-AdbCapture shell cat $remotePath) -join "`n"
        return ConvertFrom-UiHierarchyOutput -Output $output
    } finally {
        $null = Invoke-AdbCapture shell rm "-f" $remotePath
    }
}

function Invoke-ExactNotificationStopAction {
    param([Parameter(Mandatory = $true)][string]$ExpectedTitle)

    Assert-Acceptance (Test-DriveNotificationPresent) (
        "Foreground notification id=$notificationId is absent before its Stop action test"
    )
    $null = Invoke-AdbCapture shell cmd statusbar expand-notifications
    $expandedOnce = $false
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $hierarchy = Read-AndroidUiHierarchy
        $row = Get-DriveNotificationRow `
            -Hierarchy $hierarchy `
            -ExpectedTitle $ExpectedTitle `
            -AllowMissing
        if ($null -eq $row) {
            Start-Sleep -Milliseconds 200
            continue
        }

        $stopActions = @(
            $row.SelectNodes(".//node[@resource-id='android:id/action0']") |
                Where-Object {
                    $_.GetAttribute("text") -ceq "Stop" -and
                        $_.GetAttribute("content-desc") -ceq "Stop"
                }
        )
        if ($stopActions.Count -eq 1) {
            $appLabels = @(
                $row.SelectNodes(".//node[@resource-id='android:id/app_name_text']") |
                    Where-Object { $_.GetAttribute("text") -ceq $appDisplayName }
            )
            Assert-Acceptance ($appLabels.Count -eq 1) (
                "Expanded '$ExpectedTitle' notification is not labeled '$appDisplayName'"
            )
            $body = @(
                $row.SelectNodes(".//node[@resource-id='android:id/text']") |
                    ForEach-Object { $_.GetAttribute("text") }
            )
            Assert-Acceptance (@($body | Where-Object { $_ -like "Driving *" }).Count -eq 1) (
                "Expanded '$ExpectedTitle' notification has an unexpected body: $($body -join ', ')"
            )
            $coordinates = @(
                Get-NodeTapCoordinates -Node $stopActions[0] -Description "notification Stop action"
            )
            $null = Invoke-AdbCapture shell input tap $coordinates[0] $coordinates[1]
            return
        }
        Assert-Acceptance ($stopActions.Count -eq 0) (
            "Found multiple exact Stop actions inside '$ExpectedTitle' notification"
        )
        Assert-Acceptance (-not $expandedOnce) (
            "'$ExpectedTitle' notification was expanded but still exposed no exact Stop action"
        )
        $expandControls = @(
            $row.SelectNodes(".//node[@resource-id='android:id/expand_button']") |
                Where-Object { $_.GetAttribute("content-desc") -ceq "Expand" }
        )
        Assert-Acceptance ($expandControls.Count -eq 1) (
            "Collapsed '$ExpectedTitle' notification has no unique Expand control"
        )
        $coordinates = @(
            Get-NodeTapCoordinates -Node $expandControls[0] -Description "notification Expand control"
        )
        $null = Invoke-AdbCapture shell input tap $coordinates[0] $coordinates[1]
        $expandedOnce = $true
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)

    throw "ACCEPTANCE FAILED: Timed out after ${TimeoutSeconds}s locating the exact notification Stop action."
}

function Wait-ServiceState {
    param(
        [Parameter(Mandatory = $true)][bool]$Running,
        [Parameter(Mandatory = $true)][string]$Description
    )

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        if ((Test-ServiceRunning) -eq $Running) { return }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "ACCEPTANCE FAILED: Timed out after ${TimeoutSeconds}s waiting for $Description."
}

function Get-AppProcessId {
    # `adb shell sh -c` quoting varies across platform-tools versions. Call pidof directly and
    # deliberately accept its exit=1 "not running" result while a killed sticky service restarts.
    $raw = @(
        & $script:AdbExecutable -s $script:SelectedSerial shell pidof $packageName 2>$null
    )
    $pidofExitCode = $LASTEXITCODE
    if ($pidofExitCode -eq 1) { return $null }
    Assert-Acceptance ($pidofExitCode -eq 0) (
        "adb pidof '$packageName' exited $pidofExitCode"
    )
    $output = ($raw | ForEach-Object { $_.ToString() }) -join ""
    $pids = @(
        $output.Trim().Split(' ', [StringSplitOptions]::RemoveEmptyEntries) |
            Where-Object { $_ -cmatch '^\d+$' }
    )
    if ($pids.Count -eq 0) { return $null }
    Assert-Acceptance ($pids.Count -eq 1) (
        "Expected one app process for '$packageName'; found: $($pids -join ', ')"
    )
    return [int]$pids[0]
}

function Wait-StickyProcessRestoration {
    param([Parameter(Mandatory = $true)][int]$PreviousPid)

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $candidate = Get-AppProcessId
        if ($null -ne $candidate -and $candidate -ne $PreviousPid -and
            (Test-ServiceRunning) -and (Test-DriveNotificationPresent)) {
            return [int]$candidate
        }
        Start-Sleep -Milliseconds 250
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw (
        "ACCEPTANCE FAILED: START_STICKY did not restore a new process, service, and notification " +
        "within ${TimeoutSeconds}s after PID $PreviousPid was killed."
    )
}

function Assert-NoCurrentAppWakeLock {
    $dump = (Invoke-AdbCapture shell dumpsys power) -join "`n"
    $section = [regex]::Match(
        $dump,
        '(?ms)^\s*Wake Locks: size=.*?(?=^\s*Suspend Blockers: size=|\z)'
    )
    Assert-Acceptance $section.Success "dumpsys power omitted the current Wake Locks section"
    Assert-Acceptance ($section.Value -notmatch [regex]::Escape($packageName)) (
        "The app owns a current wake lock despite the no-wake-lock contract: $($section.Value)"
    )
}

function Assert-DebugDiagnosticExport {
    $remotePath = "files/adb-export/drive-diagnostics.jsonl"
    $text = (Invoke-AdbCapture shell run-as $packageName cat $remotePath) -join "`n"
    $byteCount = [Text.Encoding]::UTF8.GetByteCount($text)
    Assert-Acceptance ($byteCount -gt 0 -and $byteCount -le 1024 * 1024) (
        "Debug diagnostic export must be non-empty and bounded to 1 MiB; bytes=$byteCount"
    )
    $records = @(
        $text -split "`n" |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            ForEach-Object {
                try { $_ | ConvertFrom-Json } catch {
                    throw "ACCEPTANCE FAILED: Diagnostic export contains invalid JSONL: $_"
                }
            }
    )
    Assert-Acceptance ($records.Count -ge 2) "Diagnostic export omitted its export/snapshot records"
    Assert-Acceptance (@($records | Where-Object { $_.type -ceq 'export' }).Count -eq 1) (
        "Diagnostic export must contain exactly one export record"
    )
    $snapshots = @($records | Where-Object { $_.type -ceq 'snapshot' })
    Assert-Acceptance ($snapshots.Count -eq 1 -and [string]$snapshots[0].car_id -ceq $CarId) (
        "Diagnostic export omitted the selected-car snapshot for '$CarId'"
    )
    Assert-Acceptance (
        @($records | Where-Object {
            $_.type -ceq 'event' -and [string]$_.message -match '\bmark_crackle\b'
        }).Count -ge 1
    ) "Diagnostic export omitted the MARK CRACKLE event"
    return $byteCount
}

function Invoke-ExactRecentsDismissal {
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_APP_SWITCH
    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $hierarchy = Read-AndroidUiHierarchy
        $snapshots = @(
            $hierarchy.SelectNodes(
                "//node[@resource-id='com.google.android.apps.nexuslauncher:id/snapshot']"
            ) | Where-Object { $_.GetAttribute('content-desc') -ceq $appDisplayName }
        )
        if ($snapshots.Count -eq 1) {
            $task = $snapshots[0].ParentNode
            Assert-Acceptance (
                $task.GetAttribute('resource-id') -ceq
                    'com.google.android.apps.nexuslauncher:id/task_view_single'
            ) "The app Recents snapshot is not inside one task card"
            $coordinates = @(Get-NodeTapCoordinates -Node $task -Description 'app Recents task card')
            $bounds = $task.GetAttribute('bounds')
            $match = [regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
            Assert-Acceptance $match.Success "App Recents task card has invalid bounds '$bounds'"
            $top = [int]$match.Groups[2].Value
            $dismissY = [Math]::Max(0, $top - 80)
            $null = Invoke-AdbCapture shell input swipe `
                $coordinates[0] $coordinates[1] $coordinates[0] $dismissY 400
            return
        }
        Assert-Acceptance ($snapshots.Count -eq 0) (
            "Found multiple Recents snapshots for '$appDisplayName'"
        )
        Start-Sleep -Milliseconds 200
    } while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds)
    throw "ACCEPTANCE FAILED: Timed out locating '$appDisplayName' in Recents."
}

function Dismiss-DeprecatedTargetDialog {
    $hierarchy = Read-AndroidUiHierarchy
    $button = $hierarchy.SelectSingleNode(
        "//node[@resource-id='android:id/button1' or @text='OK']"
    )
    Assert-Acceptance ($null -ne $button) (
        "The compatibility dialog is visible, but its affirmative android:id/button1/OK control was not found"
    )
    $bounds = [string]$button.GetAttribute("bounds")
    $match = [regex]::Match($bounds, '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$')
    Assert-Acceptance $match.Success "Compatibility-dialog button has invalid bounds '$bounds'"
    $left = [int]$match.Groups[1].Value
    $top = [int]$match.Groups[2].Value
    $right = [int]$match.Groups[3].Value
    $bottom = [int]$match.Groups[4].Value
    Assert-Acceptance ($right -gt $left -and $bottom -gt $top) (
        "Compatibility-dialog button has empty bounds '$bounds'"
    )
    $null = Invoke-AdbCapture shell input tap (($left + $right) / 2) (($top + $bottom) / 2)

    $watch = [Diagnostics.Stopwatch]::StartNew()
    do {
        $windows = (Invoke-AdbCapture shell dumpsys window windows) -join "`n"
        if ($windows -notmatch "DeprecatedTargetSdkVersionDialog") { return }
        Start-Sleep -Milliseconds 100
    } while ($watch.Elapsed.TotalSeconds -lt 5)
    throw "ACCEPTANCE FAILED: Android's compatibility dialog remained visible after tapping its OK control"
}

function Start-DashboardActivity {
    $result = (Invoke-AdbCapture shell am start "-W" "-n" $activityComponent) -join "`n"
    Assert-Acceptance ($result -notmatch "(?im)^Error:") "Activity launch failed: $result"
    Assert-Acceptance ($result -match "(?im)^Status:\s+ok\s*$") "Activity did not report Status: ok: $result"
    $totalTimeMatch = [regex]::Match($result, '(?im)^TotalTime:\s+(\d+)\s*$')
    Assert-Acceptance $totalTimeMatch.Success "Activity launch omitted TotalTime: $result"
    $totalTimeMillis = [int]$totalTimeMatch.Groups[1].Value
    Assert-Acceptance ($totalTimeMillis -lt 4500) (
        "Activity launch took ${totalTimeMillis}ms. Runtime/catalog initialization must remain " +
        "off the main thread and below Android's 5-second input-dispatch ANR boundary."
    )
    $windows = (Invoke-AdbCapture shell dumpsys window windows) -join "`n"
    if ($windows -match "DeprecatedTargetSdkVersionDialog") {
        # The API 36 test emulator presents this one-time compatibility warning for the BYD-required
        # targetSdk 25. Resolve and tap the actual affirmative control through the ADB hierarchy;
        # a generic Enter key can focus the dialog's unrelated update action.
        Dismiss-DeprecatedTargetDialog
    }
    $script:RuntimeMayBeRunning = $true
    Wait-ServiceState -Running $true -Description "DriveRuntimeService to start"
}

function Set-AndVerifyMutedMedia {
    $null = Invoke-AdbCapture shell cmd media_session volume --stream 3 --set 0
    $volume = (Invoke-AdbCapture shell cmd media_session volume --stream 3 --get) -join "`n"
    Assert-Acceptance ($volume -match "(?im)volume is 0 in range") (
        "Could not prove emulator STREAM_MUSIC is muted. Output: $volume"
    )
}

function Write-Step {
    param([Parameter(Mandatory = $true)][string]$Message)
    Write-Host "==> $Message" -ForegroundColor Cyan
}

$apk = $null
$catalog = $null
$pack = $null
$packDescriptor = $null
$carDisplayName = $null
$temporaryFixtureDirectory = $null
$installedFingerprint = $null
$freshCoreSteps = $null
$freshAudioFrames = $null

try {
    Assert-Acceptance (Test-Path -LiteralPath $adbDrivePath -PathType Leaf) "Missing helper: $adbDrivePath"
    $apk = Resolve-RequiredFile -Path $ApkPath -Label "APK"
    $catalog = Resolve-RequiredFile -Path $CatalogPath -Label "Catalog"
    $pack = Resolve-RequiredFile -Path $PackPath -Label "Pack"
    $packDescriptor = Get-AclibDescriptor -Path $pack
    $carDisplayName = Get-CatalogCarDisplayName -Path $catalog -ExpectedCarId $CarId
    $validPackOriginalHash = (Get-FileHash -LiteralPath $pack -Algorithm SHA256).Hash

    $adbCommand = Get-Command adb -CommandType Application -ErrorAction Stop |
        Select-Object -First 1
    $script:AdbExecutable = $adbCommand.Source
    $env:PATH = "$(Split-Path -Parent $script:AdbExecutable);$env:PATH"

    if ([string]::IsNullOrWhiteSpace($Serial)) {
        $deviceLines = @(& $script:AdbExecutable devices)
        $emulators = @(
            $deviceLines |
                ForEach-Object { $_.ToString() } |
                Where-Object { $_ -match "^(emulator-\d+)\s+device$" } |
                ForEach-Object { [regex]::Match($_, "^(emulator-\d+)").Groups[1].Value }
        )
        Assert-Acceptance ($emulators.Count -eq 1) (
            "Specify -Serial when exactly one online emulator is not available. Found: $($emulators -join ', ')"
        )
        $script:SelectedSerial = $emulators[0]
    } else {
        $script:SelectedSerial = $Serial.Trim()
    }

    Write-Step "Verify dedicated emulator and force STREAM_MUSIC to zero"
    Assert-Acceptance (((Invoke-AdbCapture get-state) -join "").Trim() -eq "device") (
        "ADB target '$($script:SelectedSerial)' is not online"
    )
    Assert-Acceptance (((Invoke-AdbCapture shell getprop ro.kernel.qemu) -join "").Trim() -eq "1") (
        "Target '$($script:SelectedSerial)' is not an emulator (ro.kernel.qemu != 1); refusing to install or drive"
    )
    $sdkText = ((Invoke-AdbCapture shell getprop ro.build.version.sdk) -join "").Trim()
    $deviceSdk = 0
    Assert-Acceptance ([int]::TryParse($sdkText, [ref]$deviceSdk)) "Invalid emulator SDK value '$sdkText'"
    Set-AndVerifyMutedMedia

    if (-not $SkipInstall) {
        Write-Step "Install/replace debug APK"
        $installArguments = @("install", "-r", "-d", "-t")
        if ($deviceSdk -ge 34) { $installArguments += "--bypass-low-target-sdk-block" }
        $installArguments += $apk
        $installResult = (Invoke-AdbCapture @installArguments) -join "`n"
        Assert-Acceptance ($installResult -match "(?im)^Success\s*$") "APK installation did not report Success: $installResult"
    } else {
        Write-Step "Skip APK installation by request"
    }

    $packageDump = (Invoke-AdbCapture shell dumpsys package $packageName) -join "`n"
    Assert-Acceptance ($packageDump -match "DriveDebugReceiver") (
        "Installed package is missing the debug-only ADB receiver; install the debug APK"
    )

    if ($deviceSdk -ge 33) {
        Write-Step "Grant and verify Android 13+ notification permission"
        $null = Invoke-AdbCapture shell pm grant --user 0 $packageName android.permission.POST_NOTIFICATIONS
        $packageDump = (Invoke-AdbCapture shell dumpsys package $packageName) -join "`n"
        Assert-Acceptance (
            $packageDump -match "android\.permission\.POST_NOTIFICATIONS:\s+granted=true"
        ) "POST_NOTIFICATIONS was not granted on API $deviceSdk"
    }

    $null = Invoke-AdbCapture shell am force-stop $packageName
    Set-AndVerifyMutedMedia

    Write-Step "Launch dashboard and wait for its foreground runtime"
    Start-DashboardActivity
    $initial = Wait-SnapshotCondition -Description "visible dashboard state" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "true" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true"
    }

    Write-Step "Stage and import private official catalog through adb-drive.ps1"
    $null = Invoke-DriveAndWait `
        -DriveCommand "import-catalog" `
        -DebugCommand "import_catalog" `
        -DriveArguments @("-Path", $catalog)

    Write-Step "Stage, validate, and import private sound pack through adb-drive.ps1"
    $null = Invoke-DriveAndWait `
        -DriveCommand "import-pack" `
        -DebugCommand "import_pack" `
        -DriveArguments @("-Path", $pack)

    Write-Step "Select '$CarId' and wait for complete native activation"
    $null = Invoke-DriveAndWait `
        -DriveCommand "car" `
        -DebugCommand "select_car" `
        -DriveArguments @("-CarId", $CarId)
    $active = Wait-SnapshotCondition -Description "selected car pack to become ACTIVE" -Predicate {
        param($state)
        $decoded = 0L
        $hasDecoded = $state.ContainsKey("decoded_bytes") -and
            [long]::TryParse([string]$state["decoded_bytes"], [ref]$decoded)
        $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            $hasDecoded -and $decoded -gt 0L
    }
    $null = Get-SnapshotInt64 $active "core_steps"
    $null = Get-SnapshotInt64 $active "audio_frames"

    Write-Step "Reject a byte-corrupt replacement and prove atomic installed-family rollback"
    Assert-NoPackTransactionDebris
    $beforeFingerprint = Get-InstalledFamilyFingerprint `
        -FamilyId $packDescriptor.FamilyId `
        -ExpectedMembers $packDescriptor.InstalledMembers
    $temporaryFixtureDirectory = Join-Path `
        ([IO.Path]::GetFullPath([IO.Path]::GetTempPath())) `
        ("byd-aclib-acceptance-" + [Guid]::NewGuid().ToString("N"))
    $null = New-Item -ItemType Directory -Path $temporaryFixtureDirectory
    $corruptPack = Join-Path $temporaryFixtureDirectory "corrupt-family.aclib"
    New-CorruptAclibFixture `
        -SourcePath $pack `
        -TargetMember $packDescriptor.TargetMember `
        -DestinationPath $corruptPack
    $corruptHash = (Get-FileHash -LiteralPath $corruptPack -Algorithm SHA256).Hash
    Assert-Acceptance ($corruptHash -cne $validPackOriginalHash) (
        "Corrupt fixture unexpectedly has the valid pack hash"
    )
    Assert-Acceptance (
        (Get-FileHash -LiteralPath $pack -Algorithm SHA256).Hash -ceq $validPackOriginalHash
    ) "Creating the corrupt fixture modified the original pack"
    $rejection = Invoke-DriveAndExpectError `
        -DriveCommand "import-pack" `
        -DebugCommand "import_pack" `
        -DriveArguments @("-Path", $corruptPack)
    Assert-Acceptance ($rejection -notmatch 'runtime_(?:initialization|destroyed|stopped)|stale_runtime') (
        "Corrupt pack was rejected for runtime availability rather than pack integrity: $rejection"
    )
    $afterFingerprint = Get-InstalledFamilyFingerprint `
        -FamilyId $packDescriptor.FamilyId `
        -ExpectedMembers $packDescriptor.InstalledMembers
    $beforeFingerprintText = $beforeFingerprint.Lines -join "`n"
    $afterFingerprintText = $afterFingerprint.Lines -join "`n"
    Assert-Acceptance (
        [string]::Equals($beforeFingerprintText, $afterFingerprintText, [StringComparison]::Ordinal)
    ) (
        "Rejected pack changed installed family bytes " +
        "(before=$($beforeFingerprint.AggregateSha256), after=$($afterFingerprint.AggregateSha256))"
    )
    Assert-NoPackTransactionDebris
    $activeDecodedBytes = Get-SnapshotInt64 $active "decoded_bytes"
    $afterRejectedImport = Wait-SnapshotCondition -Description "active family after corrupt-pack rejection" -Predicate {
        param($state)
        $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            $state.ContainsKey("decoded_bytes") -and
            (Get-SnapshotInt64 $state "decoded_bytes") -eq $activeDecodedBytes
    }
    $installedFingerprint = $afterFingerprint.AggregateSha256
    Remove-SafeTemporaryDirectory -Path $temporaryFixtureDirectory
    $temporaryFixtureDirectory = $null
    $null = Invoke-AdbCapture shell run-as $packageName rm "-f" "files/adb-import/family.aclib"
    Assert-Acceptance (
        (Get-FileHash -LiteralPath $pack -Algorithm SHA256).Hash -ceq $validPackOriginalHash
    ) "Corrupt import test modified the original pack"

    Write-Step "Set SIMULATOR input, DRIVE, enabled sound, and 65% throttle"
    $null = Invoke-DriveAndWait -DriveCommand "mode" -DebugCommand "set_input_mode" -DriveArguments @("-Value", "SIMULATOR")
    $null = Invoke-DriveAndWait -DriveCommand "transmission" -DebugCommand "set_transmission" -DriveArguments @("-Value", "DRIVE")
    $null = Invoke-DriveAndWait -DriveCommand "sound" -DebugCommand "set_sound_enabled" -DriveArguments @("-Value", "true")
    $null = Invoke-DriveAndWait -DriveCommand "pedals" -DebugCommand "set_pedals" -DriveArguments @("-Throttle", "0.65", "-Brake", "0")
    Set-AndVerifyMutedMedia

    $beforeHome = Wait-SnapshotCondition -Description "pre-Home driven state and monotonic counters" -Predicate {
        param($state)
        if (-not ($state.ContainsKey("mode") -and $state["mode"] -eq "SIMULATOR" -and
            $state.ContainsKey("transmission") -and $state["transmission"] -eq "DRIVE" -and
            $state.ContainsKey("throttle") -and $state.ContainsKey("core_steps") -and
            $state.ContainsKey("audio_frames"))) { return $false }
        (Get-SnapshotDouble $state "throttle") -ge 0.60 -and
            (Get-SnapshotInt64 $state "core_steps") -gt 0L -and
            (Get-SnapshotInt64 $state "audio_frames") -gt 0L
    }
    $beforeCoreSteps = Get-SnapshotInt64 $beforeHome "core_steps"
    $beforeAudioFrames = Get-SnapshotInt64 $beforeHome "audio_frames"

    Write-Step "Press Home through ADB and verify hidden UI, released pedals, and live core/audio"
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
    $background = Wait-SnapshotCondition -Description "background runtime continuity" -Predicate {
        param($state)
        if (-not ($state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "false" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true" -and
            $state.ContainsKey("throttle") -and $state.ContainsKey("brake") -and
            $state.ContainsKey("core_steps") -and $state.ContainsKey("audio_frames"))) { return $false }
        (Get-SnapshotDouble $state "throttle") -le 0.001 -and
            (Get-SnapshotDouble $state "brake") -le 0.001 -and
            (Get-SnapshotInt64 $state "core_steps") -gt $beforeCoreSteps -and
            (Get-SnapshotInt64 $state "audio_frames") -gt $beforeAudioFrames
    }
    $backgroundCoreSteps = Get-SnapshotInt64 $background "core_steps"
    $backgroundAudioFrames = Get-SnapshotInt64 $background "audio_frames"
    $backgroundUiBuilds = Get-SnapshotInt64 $background "ui_snapshot_builds"
    Assert-CounterAdvanced $beforeCoreSteps $backgroundCoreSteps "core_steps" "Home/background"
    Assert-CounterAdvanced $beforeAudioFrames $backgroundAudioFrames "audio_frames" "Home/background"

    Write-Step "Prove Compose/UI snapshot construction remains stopped while hidden"
    Start-Sleep -Seconds 2
    $backgroundIdle = Get-LiveSnapshot
    $backgroundIdleCoreSteps = Get-SnapshotInt64 $backgroundIdle "core_steps"
    $backgroundIdleAudioFrames = Get-SnapshotInt64 $backgroundIdle "audio_frames"
    $backgroundIdleUiBuilds = Get-SnapshotInt64 $backgroundIdle "ui_snapshot_builds"
    Assert-Acceptance ($backgroundIdle["ui_visible"] -eq "false") (
        "Activity became visible during the UI-sampler observation"
    )
    Assert-Acceptance ($backgroundIdleUiBuilds -eq $backgroundUiBuilds + 1L) (
        "Hidden UI constructed presentation snapshots " +
        "(before=$backgroundUiBuilds, after=$backgroundIdleUiBuilds). " +
        "Exactly one build is expected for the final explicit ADB snapshot itself."
    )
    Assert-CounterAdvanced $backgroundCoreSteps $backgroundIdleCoreSteps "core_steps" "hidden UI-sampler observation"
    Assert-CounterAdvanced $backgroundAudioFrames $backgroundIdleAudioFrames "audio_frames" "hidden UI-sampler observation"
    Assert-NoCurrentAppWakeLock
    $backgroundCoreSteps = $backgroundIdleCoreSteps
    $backgroundAudioFrames = $backgroundIdleAudioFrames

    Write-Step "Relaunch Activity and prove renderer/core phase counters did not reset"
    Start-DashboardActivity
    $returned = Wait-SnapshotCondition -Description "foreground return without counter reset" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "true" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            $state.ContainsKey("core_steps") -and (Get-SnapshotInt64 $state "core_steps") -gt $backgroundCoreSteps -and
            $state.ContainsKey("audio_frames") -and (Get-SnapshotInt64 $state "audio_frames") -gt $backgroundAudioFrames
    }
    $returnedCoreSteps = Get-SnapshotInt64 $returned "core_steps"
    $returnedAudioFrames = Get-SnapshotInt64 $returned "audio_frames"
    Assert-CounterAdvanced $backgroundCoreSteps $returnedCoreSteps "core_steps" "Activity relaunch"
    Assert-CounterAdvanced $backgroundAudioFrames $returnedAudioFrames "audio_frames" "Activity relaunch/no-phase-reset"

    Write-Step "Capture bounded JSONL diagnostics through the debug-only ADB bridge"
    $null = Invoke-DriveAndWait -DriveCommand "mark-crackle" -DebugCommand "mark_crackle"
    $null = Invoke-DriveAndWait `
        -DriveCommand "export-diagnostics" `
        -DebugCommand "export_diagnostics"
    $diagnosticExportBytes = Assert-DebugDiagnosticExport

    Write-Step "Kill the background process and prove START_STICKY restores the requested session"
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
    $null = Wait-SnapshotCondition -Description "hidden pre-pressure runtime" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "false" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true"
    }
    $null = Invoke-DriveAndWait `
        -DriveCommand "pedals" `
        -DebugCommand "set_pedals" `
        -DriveArguments @("-Throttle", "0.65", "-Brake", "0")
    $prePressure = Wait-SnapshotCondition -Description "manual pedal before process pressure" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "false" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            (Get-SnapshotDouble $state "throttle") -ge 0.60
    }
    $prePressurePid = Get-AppProcessId
    Assert-Acceptance ($null -ne $prePressurePid) "Could not resolve the app PID before pressure kill"
    # The shell UID cannot signal the app under modern SELinux. `run-as` executes only this one
    # kill as the debuggable app UID, which can signal its own process and accurately exercises
    # Android's START_STICKY recovery path without force-stop semantics.
    $null = Invoke-AdbCapture shell run-as $packageName kill "-9" $prePressurePid
    $restoredPid = Wait-StickyProcessRestoration -PreviousPid $prePressurePid
    $restored = Wait-SnapshotCondition -Description "restored sticky runtime and selected family" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "false" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            (Get-SnapshotInt64 $state "decoded_bytes") -gt 0L -and
            (Get-SnapshotDouble $state "throttle") -le 0.001 -and
            (Get-SnapshotDouble $state "brake") -le 0.001 -and
            (Get-SnapshotInt64 $state "core_steps") -gt 0L -and
            (Get-SnapshotInt64 $state "audio_frames") -gt 0L
    }
    $restoredCoreSteps = Get-SnapshotInt64 $restored "core_steps"
    $restoredAudioFrames = Get-SnapshotInt64 $restored "audio_frames"
    Assert-Acceptance ($restored["sound_enabled"] -eq "true") (
        "Sticky restoration lost the persisted sound-enabled configuration"
    )
    Assert-NoCurrentAppWakeLock

    Write-Step "Return after process restoration without restarting its fresh runtime"
    Start-DashboardActivity
    $returnedAfterPressure = Wait-SnapshotCondition -Description "foreground after sticky restoration" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "true" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            (Get-SnapshotInt64 $state "core_steps") -gt $restoredCoreSteps -and
            (Get-SnapshotInt64 $state "audio_frames") -gt $restoredAudioFrames
    }
    $returnedCoreSteps = Get-SnapshotInt64 $returnedAfterPressure "core_steps"
    $returnedAudioFrames = Get-SnapshotInt64 $returnedAfterPressure "audio_frames"

    Write-Step "Tap the exact foreground-notification Stop action through ADB UI hierarchy"
    Set-AndVerifyMutedMedia
    Wait-DriveNotificationState -Present $true -Description "foreground drive notification"
    Invoke-ExactNotificationStopAction -ExpectedTitle $carDisplayName
    $null = Invoke-AdbCapture shell cmd statusbar collapse
    # A stopped started-service legitimately remains instantiated while an Activity is still bound.
    # Move that Activity to the background so onStop() unbinds; teardown must then complete. The
    # stop request itself has already come exclusively from the notification PendingIntent action.
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
    Wait-ServiceState -Running $false -Description "notification Stop DriveRuntimeService teardown"
    Wait-DriveNotificationState -Present $false -Description "foreground notification removal"

    $sessionXml = (Invoke-AdbCapture shell run-as $packageName cat shared_prefs/drive_runtime_session.xml) -join "`n"
    Assert-Acceptance (
        $sessionXml -match 'name="session_requested"\s+value="false"'
    ) "Session store did not persist session_requested=false after Stop: $sessionXml"
    Assert-Acceptance (
        $sessionXml -match 'name="stopped_by_user"\s+value="true"'
    ) "Session store did not persist stopped_by_user=true after Stop: $sessionXml"

    for ($observation = 1; $observation -le 5; $observation++) {
        Start-Sleep -Seconds 1
        Assert-Acceptance (-not (Test-ServiceRunning)) (
            "DriveRuntimeService resurrected during sticky-suppression observation $observation/5"
        )
        Assert-Acceptance (-not (Test-DriveNotificationPresent)) (
            "Drive notification resurrected during sticky-suppression observation $observation/5"
        )
    }
    $script:StopVerified = $true
    $script:RuntimeMayBeRunning = $false

    Write-Step "Explicitly reopen Activity and prove a fresh session starts after notification Stop"
    $script:StopVerified = $false
    Start-DashboardActivity
    $fresh = Wait-SnapshotCondition -Description "explicit fresh session after notification Stop" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "true" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            $state.ContainsKey("decoded_bytes") -and (Get-SnapshotInt64 $state "decoded_bytes") -gt 0L -and
            $state.ContainsKey("core_steps") -and (Get-SnapshotInt64 $state "core_steps") -gt 0L -and
            $state.ContainsKey("audio_frames") -and (Get-SnapshotInt64 $state "audio_frames") -gt 0L
    }
    $freshCoreSteps = Get-SnapshotInt64 $fresh "core_steps"
    $freshAudioFrames = Get-SnapshotInt64 $fresh "audio_frames"
    Assert-Acceptance ($freshCoreSteps -lt $returnedCoreSteps) (
        "Explicit reopen reused old core phase ($freshCoreSteps is not below $returnedCoreSteps)"
    )
    Assert-Acceptance ($freshAudioFrames -lt $returnedAudioFrames) (
        "Explicit reopen reused old audio phase ($freshAudioFrames is not below $returnedAudioFrames)"
    )
    $reopenedSessionXml = (
        Invoke-AdbCapture shell run-as $packageName cat shared_prefs/drive_runtime_session.xml
    ) -join "`n"
    Assert-Acceptance (
        $reopenedSessionXml -match 'name="session_requested"\s+value="true"'
    ) "Explicit reopen did not persist session_requested=true: $reopenedSessionXml"
    Assert-Acceptance (
        $reopenedSessionXml -match 'name="stopped_by_user"\s+value="false"'
    ) "Explicit reopen did not clear stopped_by_user: $reopenedSessionXml"
    Wait-DriveNotificationState -Present $true -Description "fresh-session foreground notification"

    Write-Step "Dismiss the exact app card from Recents and prove onTaskRemoved shutdown"
    Invoke-ExactRecentsDismissal
    Wait-ServiceState -Running $false -Description "Recents-dismissal DriveRuntimeService teardown"
    Wait-DriveNotificationState -Present $false -Description "Recents-dismissal notification removal"
    $recentsSessionXml = (
        Invoke-AdbCapture shell run-as $packageName cat shared_prefs/drive_runtime_session.xml
    ) -join "`n"
    Assert-Acceptance (
        $recentsSessionXml -match 'name="session_requested"\s+value="false"'
    ) "Recents dismissal did not persist session_requested=false: $recentsSessionXml"
    Assert-Acceptance (
        $recentsSessionXml -match 'name="stopped_by_user"\s+value="true"'
    ) "Recents dismissal did not persist stopped_by_user=true: $recentsSessionXml"
    for ($observation = 1; $observation -le 5; $observation++) {
        Start-Sleep -Seconds 1
        Assert-Acceptance (-not (Test-ServiceRunning)) (
            "DriveRuntimeService resurrected after Recents dismissal $observation/5"
        )
        Assert-Acceptance (-not (Test-DriveNotificationPresent)) (
            "Drive notification resurrected after Recents dismissal $observation/5"
        )
    }
    $script:StopVerified = $true
    $script:RuntimeMayBeRunning = $false

    Write-Step "Explicitly reopen after Recents dismissal, then cleanly stop the new session"
    $script:StopVerified = $false
    Start-DashboardActivity
    $afterRecents = Wait-SnapshotCondition -Description "explicit fresh session after Recents dismissal" -Predicate {
        param($state)
        $state.ContainsKey("ui_visible") -and $state["ui_visible"] -eq "true" -and
            $state.ContainsKey("runtime_started") -and $state["runtime_started"] -eq "true" -and
            $state.ContainsKey("car") -and $state["car"] -eq $CarId -and
            $state.ContainsKey("pack_status") -and $state["pack_status"] -eq "ACTIVE" -and
            (Get-SnapshotInt64 $state "decoded_bytes") -gt 0L -and
            (Get-SnapshotInt64 $state "core_steps") -gt 0L -and
            (Get-SnapshotInt64 $state "audio_frames") -gt 0L
    }
    $recentsFreshCoreSteps = Get-SnapshotInt64 $afterRecents "core_steps"
    $recentsFreshAudioFrames = Get-SnapshotInt64 $afterRecents "audio_frames"
    $afterRecentsSessionXml = (
        Invoke-AdbCapture shell run-as $packageName cat shared_prefs/drive_runtime_session.xml
    ) -join "`n"
    Assert-Acceptance (
        $afterRecentsSessionXml -match 'name="session_requested"\s+value="true"'
    ) "Explicit reopen after Recents did not persist session_requested=true: $afterRecentsSessionXml"
    Assert-Acceptance (
        $afterRecentsSessionXml -match 'name="stopped_by_user"\s+value="false"'
    ) "Explicit reopen after Recents did not clear stopped_by_user: $afterRecentsSessionXml"

    Write-Step "Shut down the final verified session through the debug-only cleanup command"
    $null = Invoke-DriveAndWait -DriveCommand "stop" -DebugCommand "stop_runtime"
    $null = Invoke-AdbCapture shell input keyevent KEYCODE_HOME
    Wait-ServiceState -Running $false -Description "fresh-session cleanup"
    Wait-DriveNotificationState -Present $false -Description "fresh-session notification cleanup"
    $script:StopVerified = $true
    $script:RuntimeMayBeRunning = $false

    Write-Host ""
    Write-Host "PASS: muted ADB-only emulator lifecycle acceptance" -ForegroundColor Green
    Write-Host "  emulator: $($script:SelectedSerial) (API $deviceSdk)"
    Write-Host "  car: $CarId"
    Write-Host "  core_steps: $beforeCoreSteps -> $backgroundCoreSteps -> $returnedCoreSteps"
    Write-Host "  audio_frames: $beforeAudioFrames -> $backgroundAudioFrames -> $returnedAudioFrames"
    Write-Host "  sticky_process: pid=$prePressurePid -> $restoredPid core=$restoredCoreSteps audio=$restoredAudioFrames"
    Write-Host "  hidden_ui_snapshot_builds: $backgroundUiBuilds -> $backgroundIdleUiBuilds (one explicit probe only)"
    Write-Host "  diagnostic_jsonl_bytes: $diagnosticExportBytes"
    Write-Host "  fresh_session: core_steps=$freshCoreSteps audio_frames=$freshAudioFrames"
    Write-Host "  recents_fresh_session: core_steps=$recentsFreshCoreSteps audio_frames=$recentsFreshAudioFrames"
    Write-Host "  installed_family_fingerprint: $installedFingerprint"
    Write-Host "  notification_stop: exact action + 5s no-resurrection + explicit reopen"
    Write-Host "  recents_stop: exact app-card swipe + 5s no-resurrection + explicit reopen"
    Write-Host "  apk_sha256: $((Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash)"
    Write-Host "  catalog_sha256: $((Get-FileHash -LiteralPath $catalog -Algorithm SHA256).Hash)"
    Write-Host "  pack_sha256: $((Get-FileHash -LiteralPath $pack -Algorithm SHA256).Hash)"
} catch {
    Write-Error $_.Exception.Message
    exit 1
} finally {
    if ($null -ne $temporaryFixtureDirectory) {
        try {
            Remove-SafeTemporaryDirectory -Path $temporaryFixtureDirectory
        } catch {
            Write-Warning "Could not remove the private corrupt-pack fixture: $($_.Exception.Message)"
        }
    }
    if ($script:RuntimeMayBeRunning -and -not $script:StopVerified -and
        $null -ne $script:SelectedSerial -and $null -ne $script:AdbExecutable) {
        try {
            $null = Invoke-DriveCommand -Command "reset-pedals"
            $null = Invoke-DriveCommand -Command "stop"
            Start-Sleep -Seconds 3
            if (Test-ServiceRunning) {
                $null = Invoke-AdbCapture shell am force-stop $packageName
            }
        } catch {
            Write-Warning "Best-effort failure cleanup could not stop the debug runtime: $($_.Exception.Message)"
            try {
                $null = Invoke-AdbCapture shell am force-stop $packageName
            } catch {
                Write-Warning "ADB force-stop fallback also failed: $($_.Exception.Message)"
            }
        }
    }
    if ($null -ne $script:SelectedSerial -and $null -ne $script:AdbExecutable) {
        try {
            $null = Invoke-AdbCapture shell run-as $packageName rm "-f" `
                "files/adb-import/family.aclib" `
                "files/adb-import/catalog-v1.json" `
                "files/adb-export/drive-diagnostics.jsonl"
        } catch {
            Write-Warning "Could not remove app-private ADB staging files: $($_.Exception.Message)"
        }
    }
    $env:PATH = $script:OriginalPath
}
