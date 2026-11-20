param(
  [string]$Project = "D:\AsterionDownloaderFourProvider20261120",
  [string]$SourceCommit = "",
  [string]$Filename = "AsterionDownloader-FourSources-OwnerSigned.apk"
)
$ErrorActionPreference = "Stop"
$apk = Join-Path $Project "app\build\outputs\apk\acceptance\app-acceptance.apk"
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio1\jbr"
$env:ANDROID_HOME = "C:\Users\mimim\AppData\Local\Android\Sdk"
try {
    if ($SourceCommit) {
        git -C $Project fetch origin repair/downloads-nine-providers-backfill-diagnostics-20261116
        if ($LASTEXITCODE -ne 0) { throw "Could not retrieve source branch" }
        git -C $Project checkout --detach $SourceCommit
        if ($LASTEXITCODE -ne 0) { throw "Could not select exact source commit" }
    }
    Write-Output ("BUILDING_SOURCE=" + (& git -C $Project rev-parse HEAD))
    & (Join-Path $Project "tools\build_owner_signed_acceptance.ps1") -Project $Project -SignerDirectory "D:\AsterionControlRelease\signing\downloader-acceptance"
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $apk)) {
        throw "Original-key APK build failed"
    }
    $signer = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Filter apksigner.bat -File -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    if (!$signer) { throw "Android signature verifier unavailable" }
    $verify = @(& $signer verify --print-certs $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Android APK signature rejected" }
    $line = $verify | Where-Object { $_ -match "certificate SHA-256 digest:" } | Select-Object -First 1
    if (!$line) { throw "APK signer fingerprint missing" }
    $fingerprint = (($line -split "certificate SHA-256 digest:")[-1]).Trim().ToLowerInvariant()
    $original = "8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19"
    if ($fingerprint -ne $original) { throw "APK key does not match installed owner certificate" }
    Write-Output ("ORIGINAL_APP_SIGNER=" + $fingerprint)
    $driver = Join-Path $Project "tools\native_wifi_owner_apk_transfer.py"
    & "D:\AsterionControlRelease\.venv\Scripts\python.exe" $driver $apk $Filename
    if ($LASTEXITCODE -ne 0) { throw "Verified phone transfer failed" }
    Write-Output "PHONE_MEDIASTORE_SHA256_VERIFIED=true"
} finally {
    # A PC APK is a temporary build artifact, not a persistent deliverable.
    if (Test-Path -LiteralPath $apk) {
        Remove-Item -LiteralPath $apk -Force -ErrorAction Stop
    }
    Write-Output "PC_TEMP_APK_REMOVED=true"
}
