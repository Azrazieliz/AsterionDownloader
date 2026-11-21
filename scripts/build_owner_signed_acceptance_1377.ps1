# Asterion Downloader v1.3.77: retained owner-signed Acceptance recovery.
# Reuse existing Windows DPAPI-protected signer, never print credentials.
$ErrorActionPreference='Stop'
$w='D:\AsterionDownloaderReleaseVerify_20261120'
$gradle='C:\Users\mimim\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat'
$ks='D:\AsterionControlRelease\signing\downloader-acceptance\asterion-downloader-acceptance.p12'
$dp='D:\AsterionControlRelease\signing\downloader-acceptance\password.dpapi'
$apk=Join-Path $w 'app\build\outputs\apk\acceptance\app-acceptance.apk'
# Release filename is set after the exact source SHA has been validated.
$log='D:\AsterionControlRelease\diagnostics\downloader_1377_signed_build_20261121.log'
$expected='8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19'
if (-not (Test-Path $ks) -or -not (Test-Path $dp)) { throw 'Existing stable signer missing' }
$head=(& git -C $w rev-parse HEAD).Trim()
if ($head.Length -ne 40) { throw 'Source SHA is invalid' }
$dest=Join-Path $HOME ('Downloads\AsterionDownloader-v1.3.77-Recovery-'+$head.Substring(0,8)+'-20261121.apk')
$version=Get-Content (Join-Path $w 'app\build.gradle.kts') -Raw
if ($version -notmatch 'versionCode\s*=\s*106' -or $version -notmatch 'versionName\s*=\s*"1.3.77"') {
    throw 'Wrong source version: refusing to create APK'
}
Write-Output "SOURCE_SHA=$head"
Add-Type -AssemblyName System.Security
$secretBytes=[IO.File]::ReadAllBytes($dp)
$pw=[Text.Encoding]::UTF8.GetString(
    [Security.Cryptography.ProtectedData]::Unprotect(
       $secretBytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser))
if ($pw.Length -lt 12) { throw 'Signer password could not be read' }
$env:ASTERION_PREVIEW_KEYSTORE=$ks
$env:ASTERION_PREVIEW_STORE_PASSWORD=$pw
$env:ASTERION_PREVIEW_KEY_PASSWORD=$pw
$keytool=(Get-Command keytool.exe -ErrorAction Stop).Source
$listing=@(& $keytool -list -v -keystore $ks -storetype PKCS12 -storepass:env ASTERION_PREVIEW_STORE_PASSWORD 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'Existing signing keystore could not be opened' }
$aliasLine=($listing | Where-Object { $_ -match '^Alias name:' } | Select-Object -First 1)
if (-not $aliasLine) { throw 'Private key alias missing' }
$alias=($aliasLine -replace '^Alias name:\s*','').Trim()
$cert=($listing | Where-Object { $_ -match 'SHA256:' } | Select-Object -First 1)
if (-not $cert) { throw 'Keystore certificate fingerprint unavailable' }
$finger=(($cert -split 'SHA256:')[-1] -replace '[^A-Fa-f0-9]','').ToLowerInvariant()
if ($finger -ne $expected) { throw "Keystore certificate mismatches installed update fingerprint: $finger" }
$env:ASTERION_PREVIEW_KEY_ALIAS=$alias
Write-Output "SIGNER_CERTIFICATE_VERIFIED=$finger"
# Gradle emits Android warnings on stderr even on success; PowerShell
# ErrorActionPreference=Stop must not mistake those for fatal script errors.
$ErrorActionPreference='Continue'
& $gradle -p $w :app:assembleAcceptance --offline --no-daemon --console=plain *> $log
$rc=$LASTEXITCODE
$ErrorActionPreference='Stop'
Get-Content $log -Tail 45
if ($rc -ne 0) { throw "Gradle assembleAcceptance failed: $rc" }
if (-not(Test-Path $apk)) { throw 'APK output missing' }
$signer=Get-ChildItem -Path (Join-Path $env:ANDROID_HOME 'build-tools') -Filter apksigner.bat -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $signer) { throw 'Android apksigner missing' }
$check=@(& $signer.FullName verify --print-certs $apk 2>&1)
if ($LASTEXITCODE -ne 0) { throw 'New APK signature verification failed' }
$certificate=($check | Where-Object { $_ -match 'certificate SHA-256 digest:' } | Select-Object -First 1)
if (-not $certificate) { throw 'No new APK cert digest' }
$newFinger=(($certificate -split 'certificate SHA-256 digest:')[-1]).Trim().ToLowerInvariant()
if ($newFinger -ne $expected) { throw "New APK is not upgrade-signature-compatible: $newFinger" }
$aapt=Get-ChildItem -Path (Join-Path $env:ANDROID_HOME 'build-tools') -Filter aapt.exe -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if ($aapt) {
    $manifest=@(& $aapt.FullName dump badging $apk 2>&1)
    $package=($manifest | Where-Object { $_ -match '^package:' } | Select-Object -First 1)
    Write-Output "APK_PACKAGE=$package"
    if ($package -notmatch "name='com.azrael.pixivdumpsync.acceptance'" -or
        $package -notmatch "versionCode='106'") {
        throw 'APK package ID or version differs from intended in-place upgrade'
    }
}
Copy-Item -LiteralPath $apk -Destination $dest -Force
$sourceHash=(Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$stagedHash=(Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant()
if ($sourceHash -ne $stagedHash) { throw 'Staged copy SHA256 mismatch' }
Write-Output "SIGNED_APK_STAGED=$dest"
Write-Output "SIGNED_APK_BYTES=$((Get-Item $dest).Length)"
Write-Output "SIGNED_APK_SHA256=$sourceHash"
Write-Output 'SIGNED_COMPATIBLE_READY_FOR_PHONE_TRANSFER=true'
