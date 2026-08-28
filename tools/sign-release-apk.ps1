<#
.SYNOPSIS
Signs an assembled release APK with an explicitly supplied local keystore.

.DESCRIPTION
The Gradle release build intentionally contains no signing configuration. This script keeps
keystore paths and passwords outside the repository, asks Android's apksigner for both a JAR/v1 and
APK Signature Scheme v2 signature, then verifies both. Signature coverage begins at API 23 only to
force apksigner to retain v1; the application's manifest still controls its actual API 25 minimum.

Passwords are read only from environment variables whose names are supplied below. Their values
are never accepted as command-line arguments or printed. The output APK is also expected to remain
outside the repository; *.apk and signing material are gitignored as a second line of defence.

.EXAMPLE
$env:BYD_RELEASE_KEYSTORE_PASSWORD = '<secret>'
$env:BYD_RELEASE_KEY_PASSWORD = '<secret>'
.\tools\sign-release-apk.ps1 `
  -UnsignedApk .\mobile\build\outputs\apk\release\engine-sounds-simulator-build-123-release.apk `
  -OutputApk D:\private\releases\BYDMotorSound-123-release-signed.apk `
  -Keystore D:\private\signing\byd-release.jks `
  -Alias byd-release
Remove-Item Env:BYD_RELEASE_KEYSTORE_PASSWORD,Env:BYD_RELEASE_KEY_PASSWORD
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$UnsignedApk,

    [Parameter(Mandatory = $true)]
    [string]$OutputApk,

    [Parameter(Mandatory = $true)]
    [string]$Keystore,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Alias,

    [ValidateNotNullOrEmpty()]
    [string]$KeystorePasswordEnvironmentVariable = "BYD_RELEASE_KEYSTORE_PASSWORD",

    [ValidateNotNullOrEmpty()]
    [string]$KeyPasswordEnvironmentVariable = "BYD_RELEASE_KEY_PASSWORD",

    [string]$AndroidSdkRoot,

    [string]$ApkSignerPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RequiredFile {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )
    $resolved = (Resolve-Path -LiteralPath $Path -ErrorAction Stop).Path
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
        throw "$Label must be a file: $Path"
    }
    return $resolved
}

function Read-LocalAndroidSdkRoot {
    $localProperties = Join-Path $PSScriptRoot "..\local.properties"
    $sdkLine = Get-Content -LiteralPath $localProperties -ErrorAction SilentlyContinue |
        Where-Object { $_ -match '^sdk\.dir=(.+)$' } |
        Select-Object -First 1
    if ($sdkLine -and $sdkLine -match '^sdk\.dir=(.+)$') {
        return $Matches[1].Replace('\:', ':').Replace('\\', '\')
    }
    return $null
}

function Resolve-ApkSigner {
    if ($ApkSignerPath) {
        return Resolve-RequiredFile -Path $ApkSignerPath -Label "apksigner"
    }

    $configuredSdkRoot = if ($AndroidSdkRoot) { $AndroidSdkRoot } else { Read-LocalAndroidSdkRoot }
    $sdkCandidates = @(
        $configuredSdkRoot,
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk" })
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Container) } |
        Select-Object -Unique

    foreach ($sdkRoot in $sdkCandidates) {
        $candidates = Get-ChildItem -LiteralPath (Join-Path $sdkRoot "build-tools") `
                -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path $_.FullName "apksigner.bat" } |
            Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } |
            Sort-Object -Descending
        if ($candidates) {
            return [string]($candidates | Select-Object -First 1)
        }
    }
    throw "apksigner was not found. Supply -ApkSignerPath or -AndroidSdkRoot."
}

function Require-SecretEnvironmentVariable {
    param([Parameter(Mandatory = $true)][string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name, "Process")
    if ([string]::IsNullOrEmpty($value)) {
        throw "Required process environment variable '$Name' is missing or empty."
    }
}

$sourceApk = Resolve-RequiredFile -Path $UnsignedApk -Label "Unsigned APK"
$keystorePath = Resolve-RequiredFile -Path $Keystore -Label "Keystore"
$signer = Resolve-ApkSigner
Require-SecretEnvironmentVariable -Name $KeystorePasswordEnvironmentVariable
Require-SecretEnvironmentVariable -Name $KeyPasswordEnvironmentVariable

$destination = [IO.Path]::GetFullPath($OutputApk)
if ([string]::Equals($sourceApk, $destination, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Output APK must differ from the unsigned input APK."
}
$destinationDirectory = Split-Path -Parent $destination
if (-not $destinationDirectory) {
    throw "Output APK must include a parent directory."
}
[IO.Directory]::CreateDirectory($destinationDirectory) | Out-Null
$signatureCoverageMinimumSdk = 23

$signArguments = @(
    "sign",
    "--out", $destination,
    "--ks", $keystorePath,
    "--ks-key-alias", $Alias,
    "--ks-pass", "env:$KeystorePasswordEnvironmentVariable",
    "--key-pass", "env:$KeyPasswordEnvironmentVariable",
    "--min-sdk-version", "$signatureCoverageMinimumSdk",
    "--v1-signing-enabled", "true",
    "--v2-signing-enabled", "true",
    "--v3-signing-enabled", "false",
    "--v4-signing-enabled", "false",
    $sourceApk
)
& $signer @signArguments
if ($LASTEXITCODE -ne 0) {
    throw "apksigner sign failed with exit code $LASTEXITCODE"
}

$verification = & $signer verify --verbose --print-certs `
    --min-sdk-version $signatureCoverageMinimumSdk $destination 2>&1
if ($LASTEXITCODE -ne 0) {
    throw "apksigner verification failed with exit code $LASTEXITCODE`n$($verification -join [Environment]::NewLine)"
}
$verificationText = $verification -join [Environment]::NewLine
if ($verificationText -notmatch '(?m)^Verified using v1 scheme .*: true\r?$' -or
    $verificationText -notmatch '(?m)^Verified using v2 scheme .*: true\r?$') {
    throw "Signed APK does not contain both required v1 and v2 signatures.`n$verificationText"
}

$artifact = Get-Item -LiteralPath $destination
[pscustomobject]@{
    Path = $artifact.FullName
    Bytes = $artifact.Length
    Sha256 = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    Signer = $signer
    V1Verified = $true
    V2Verified = $true
    Certificate = ($verification | Where-Object { $_ -match '^Signer #1 certificate DN:' } | Select-Object -First 1)
}
