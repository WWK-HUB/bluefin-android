param(
    [Parameter(Mandatory)][string]$Apk,
    [Parameter(Mandatory)][string]$BuildTools,
    [int]$VersionCode = 35,
    [string]$VersionName = '0.12.1-rc1'
)

$ErrorActionPreference = 'Stop'
$expected = '572ea4e5d924a9bc99e7e4bc1e0733ab4cce36cf906e7273be2d3aaa6cf98c6c'
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$signer = Join-Path $BuildTools 'apksigner.bat'
$aapt = Join-Path $BuildTools 'aapt.exe'
$certs = & $signer verify --print-certs $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
$fingerprints = @($certs | ForEach-Object {
    if ($_ -match '^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})') { $Matches[1] }
})
if ($fingerprints.Count -ne 1 -or $fingerprints[0] -ne $expected) {
    throw 'APK signer differs from the installed update channel.'
}
$badging = & $aapt dump badging $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK manifest inspection failed.' }
$identity = "package: name='com.wwkhub.bluefin' versionCode='$VersionCode' versionName='$VersionName'"
if (!$badging[0].StartsWith($identity)) { throw 'APK version or package name differs from the release.' }
if ($badging -contains 'application-debuggable') { throw 'Distributable APK must be non-debuggable.' }
if (($badging | Where-Object { $_ -match '^native-code:' }) -ne "native-code: 'arm64-v8a'") {
    throw 'Distributable APK must contain the arm64-v8a native libraries only.'
}
Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
