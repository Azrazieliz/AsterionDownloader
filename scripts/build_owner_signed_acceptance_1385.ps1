# Asterion Downloader 1.3.85 — data-preserving, retained-owner-signed acceptance APK.
# Run only in the existing owner's Windows Control environment; never print DPAPI plaintext.
param([Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{40}$')][string]$ExpectedCommit)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$w='D:\AsterionDownloaderReleaseVerify_20261120'
$gradle='C:\Users\mimim\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat'
$ks='D:\AsterionControlRelease\signing\downloader-acceptance\asterion-downloader-acceptance.p12'
$dp='D:\AsterionControlRelease\signing\downloader-acceptance\password.dpapi'
$apk=Join-Path $w 'app\build\outputs\apk\acceptance\app-acceptance.apk'
$log='D:\AsterionControlRelease\diagnostics\downloader_1385_owner_signed_build.log'
$expected='8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19'
if (-not(Test-Path $w) -or -not(Test-Path $gradle)) { throw 'Owner Windows release build environment unavailable' }
if (-not(Test-Path $ks) -or -not(Test-Path $dp)) { throw 'Existing stable signer unavailable' }
$head=(& git -C $w rev-parse HEAD).Trim().ToLowerInvariant()
if ($LASTEXITCODE -ne 0 -or $head -ne $ExpectedCommit.ToLowerInvariant()) {
    throw 'Exact release source commit mismatch'
}
$tracked=@(& git -C $w status --porcelain --untracked-files=no)
if ($LASTEXITCODE -ne 0 -or $tracked.Count -gt 0) {
    throw 'Tracked Windows worktree changes prevent safe owner build'
}
$source=Get-Content (Join-Path $w 'app\build.gradle.kts') -Raw
if ($source -notmatch 'versionCode\s*=\s*113' -or
    $source -notmatch 'versionName\s*=\s*"1\.3\.84"') {
    throw 'Wrong version for owner in-place update'
}
$wheel=Join-Path $w 'app\src\main\assets\yt_dlp-2026.10.26-py3-none-any.whl'
if ((Get-FileHash -LiteralPath $wheel -Algorithm SHA256).Hash.ToLowerInvariant() -ne
    'a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae') {
    throw 'Pinned extractor wheel missing or changed'
}
Write-Output "SOURCE_SHA=$head"
try {
    Add-Type -AssemblyName System.Security
    $plain=[Security.Cryptography.ProtectedData]::Unprotect(
        [IO.File]::ReadAllBytes($dp),$null,
        [Security.Cryptography.DataProtectionScope]::CurrentUser)
    $pw=[Text.Encoding]::UTF8.GetString($plain)
    if ($pw.Length -lt 12) { throw 'Owner DPAPI signer password unavailable' }
    $env:ASTERION_PREVIEW_KEYSTORE=$ks
    $env:ASTERION_PREVIEW_STORE_PASSWORD=$pw
    $env:ASTERION_PREVIEW_KEY_PASSWORD=$pw
    $keytool=(Get-Command keytool.exe -ErrorAction Stop).Source
    $listing=@(& $keytool -list -v -keystore $ks -storetype PKCS12 -storepass:env ASTERION_PREVIEW_STORE_PASSWORD 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot open existing owner signer' }
    $aliasLine=$listing | Where-Object {$_ -match '^Alias name:'} | Select-Object -First 1
    $certLine=$listing | Where-Object {$_ -match 'SHA256:'} | Select-Object -First 1
    if (-not $aliasLine -or -not $certLine) { throw 'Signer alias or fingerprint unavailable' }
    $finger=(($certLine -split 'SHA256:')[-1] -replace '[^A-Fa-f0-9]','').ToLowerInvariant()
    if ($finger -ne $expected) { throw 'Original owner signing certificate mismatch' }
    $env:ASTERION_PREVIEW_KEY_ALIAS=($aliasLine -replace '^Alias name:\s*','').Trim()
    Write-Output "ORIGINAL_CERTIFICATE_MATCH=true"
    # Keep Gradle diagnostics on D:, not C:. Failing tests block release.
    $ErrorActionPreference='Continue'
    & $gradle -p $w :app:verifyFrozenVideoProviders :app:testPilotUnitTest :app:testAcceptanceUnitTest :app:assembleAcceptance --offline --no-daemon --console=plain *> $log
    $rc=$LASTEXITCODE
    $ErrorActionPreference='Stop'
    Get-Content $log -Tail 55
    if ($rc -ne 0) { throw "Gradle tests/build failed: $rc" }
    if (-not(Test-Path -LiteralPath $apk)) { throw 'Signed APK missing' }
    $tools=Get-ChildItem -Path (Join-Path $env:ANDROID_HOME 'build-tools') -Directory | Sort-Object Name -Descending
    $dir=$tools | Select-Object -First 1
    if (-not $dir) { throw 'Android build tools missing' }
    $apksigner=Join-Path $dir.FullName 'apksigner.bat'
    $aapt=Join-Path $dir.FullName 'aapt.exe'
    $verify=@(& $apksigner verify --print-certs $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Produced APK signature invalid' }
    $cert=$verify | Where-Object {$_ -match 'certificate SHA-256 digest:'} | Select-Object -First 1
    $actual=(($cert -split 'certificate SHA-256 digest:')[-1]).Trim().ToLowerInvariant()
    if ($actual -ne $expected) { throw 'Produced APK does not match installed acceptance signer' }
    $badge=@(& $aapt dump badging $apk 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read APK identity' }
    $identity=$badge | Where-Object {$_ -match '^package:'} | Select-Object -First 1
    if ($identity -notmatch "name='com.azrael.pixivdumpsync.acceptance'" -or
        $identity -notmatch "versionCode='114'" -or
        $identity -notmatch "versionName='1.3.85-acceptance'") {
        throw 'APK package/version mismatch'
    }
    $dest=Join-Path $HOME ('Downloads\AsterionDownloader-v1.3.85-'+$head.Substring(0,12)+'-OwnerSigned.apk')
    $digest=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    if (Test-Path -LiteralPath $dest) {
        if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) {
            throw 'Existing output path contains different APK; no overwrite'
        }
    } else {
        Copy-Item -LiteralPath $apk -Destination $dest
    }
    if ((Get-FileHash -LiteralPath $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $digest) {
        throw 'Signed APK staging hash mismatch'
    }
    Write-Output "APK_PACKAGE=$identity"
    Write-Output "SIGNED_APK_STAGED=$dest"
    Write-Output "SIGNED_APK_BYTES=$((Get-Item -LiteralPath $dest).Length)"
    Write-Output "SIGNED_APK_SHA256=$digest"
    Write-Output 'SIGNED_COMPATIBLE_READY_FOR_PHONE_TRANSFER=true'
} finally {
    foreach ($name in @('ASTERION_PREVIEW_KEYSTORE','ASTERION_PREVIEW_STORE_PASSWORD',
                        'ASTERION_PREVIEW_KEY_ALIAS','ASTERION_PREVIEW_KEY_PASSWORD')) {
        Remove-Item -Path ('Env:'+$name) -ErrorAction SilentlyContinue
    }
}
