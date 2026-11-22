# Sign the exact CI-built acceptance APK with the installed owner's Windows
# DPAPI key; then transfer bytes via the existing Asterion Control phone link.
# Never install, uninstall, clear data, create a new key or log secrets.
param([switch]$SkipPhoneTransfer)
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'
$run='45013643087'
$repo='Azrazieliz/AsterionDownloader'
$source='20a5524bf55f04242a76f8ff845c2f72164b57d2'
$unsignedSha='309930bc5b283ee360cd83d5c512f2c020a44327289472c08ee06f55d61ac0ec'
$cert='8914af1b2713e1d7e93c52abfddf11cbc6bba8d7057906161185a4216dcc0c19'
$dir='D:\AsterionControlRelease\diagnostics\downloader-v1382-owner-pc'
$art=Join-Path $dir 'ci-original'
$unsigned=Join-Path $art 'Asterion-Downloader-v1.3.82-UNSIGNED-Owner-PC-Input.apk'
$aligned=Join-Path $dir 'aligned-acceptance.apk'
$signed=Join-Path $dir 'AsterionDownloader-v1.3.82-OwnerSigned-20a5524b.apk'
$dest=Join-Path (Join-Path $HOME 'Downloads') 'AsterionDownloader-v1.3.82-OwnerSigned-20a5524b.apk'
$ks='D:\AsterionControlRelease\signing\downloader-acceptance\asterion-downloader-acceptance.p12'
$dpapi='D:\AsterionControlRelease\signing\downloader-acceptance\password.dpapi'
if(!(Test-Path $ks) -or !(Test-Path $dpapi)){throw 'Retained owner signing identity missing; no alternative key allowed'}
New-Item -ItemType Directory -Force -Path $art | Out-Null
$gh=(Get-Command gh.exe -ErrorAction Stop).Source
& $gh run download $run --repo $repo --name 'Asterion-Downloader-v1.3.82-Acceptance-Build' --dir $art
if($LASTEXITCODE -ne 0){throw 'Exact CI acceptance artifact download failed'}
if(!(Test-Path $unsigned)){throw 'CI input is missing'}
if((Get-Content (Join-Path $art 'source-commit.txt') -Raw).Trim() -ne $source){
  throw 'Source SHA not equal to exact tested v1.3.82 build'
}
if((Get-Content (Join-Path $art 'owner-signature-verified.txt') -Raw).Trim() -ne 'false'){
  throw 'Unexpected source signing state'
}
if((Get-FileHash $unsigned -Algorithm SHA256).Hash.ToLowerInvariant() -ne $unsignedSha){
  throw 'Unsigned source checksum changed'
}
if(!$env:ANDROID_HOME){throw 'Android SDK is unavailable'}
$buildTools=Join-Path $env:ANDROID_HOME 'build-tools'
$signer=(Get-ChildItem $buildTools -Filter apksigner.bat -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName)
$align=(Get-ChildItem $buildTools -Filter zipalign.exe -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName)
$aapt=(Get-ChildItem $buildTools -Filter aapt.exe -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName)
$keytool=(Get-Command keytool.exe -ErrorAction Stop).Source
$inputInfo=@(& $aapt dump badging $unsigned 2>&1)
$entry=$inputInfo | Where-Object {$_ -match '^package:'} | Select-Object -First 1
if($LASTEXITCODE -ne 0 -or $entry -notmatch "name='com.azrael.pixivdumpsync.acceptance'" -or
   $entry -notmatch "versionCode='111'" -or $entry -notmatch "versionName='1.3.82-acceptance'"){
  throw 'Incorrect package identity/version in unsigned input'
}
Add-Type -AssemblyName System.Security
$bytes=[IO.File]::ReadAllBytes($dpapi)
$plain=[Security.Cryptography.ProtectedData]::Unprotect($bytes,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
[Array]::Clear($bytes,0,$bytes.Length)
$password=[Text.Encoding]::UTF8.GetString($plain)
[Array]::Clear($plain,0,$plain.Length)
if($password.Length -lt 12){throw 'DPAPI owner password rejected'}
try {
  $env:ASTERION_OWNER_1382_SIGNING_PASSWORD=$password
  $identity=@(& $keytool -list -v -keystore $ks -storetype PKCS12 -storepass:env ASTERION_OWNER_1382_SIGNING_PASSWORD 2>&1)
  if($LASTEXITCODE -ne 0){throw 'Owner keystore cannot be opened'}
  $aliasLine=$identity | Where-Object {$_ -match '^Alias name:'} | Select-Object -First 1
  $fingerLine=$identity | Where-Object {$_ -match 'SHA256:'} | Select-Object -First 1
  if(!$aliasLine -or !$fingerLine){throw 'Owner certificate not found'}
  $alias=($aliasLine -replace '^Alias name:\s*','').Trim()
  $finger=(($fingerLine -split 'SHA256:')[-1] -replace '[^0-9A-Fa-f]','').ToLowerInvariant()
  if($finger -ne $cert){throw 'Owner key is NOT the installed acceptance signing certificate'}
  & $align -p -f 4 $unsigned $aligned
  if($LASTEXITCODE -ne 0){throw 'APK alignment failed'}
  & $signer sign --ks $ks --ks-type PKCS12 --ks-key-alias $alias --ks-pass env:ASTERION_OWNER_1382_SIGNING_PASSWORD --key-pass env:ASTERION_OWNER_1382_SIGNING_PASSWORD --out $signed $aligned
  if($LASTEXITCODE -ne 0){throw 'Original signer refused APK'}
  $check=@(& $signer verify --verbose --print-certs $signed 2>&1)
  if($LASTEXITCODE -ne 0){throw 'Android signature verification failed'}
  $certLine=$check | Where-Object {$_ -match 'Signer #1 certificate SHA-256 digest:'} | Select-Object -First 1
  if(!$certLine){throw 'Signed certificate digest missing'}
  $signedFinger=(($certLine -split 'digest:')[-1]).Trim().ToLowerInvariant()
  if($signedFinger -ne $cert){throw 'New APK is not an in-place acceptance upgrade'}
  $copyHash=(Get-FileHash $signed -Algorithm SHA256).Hash.ToLowerInvariant()
  if(Test-Path $dest){
    if((Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant() -ne $copyHash){
      throw 'Destination contains another APK; will not overwrite'
    }
  } else {Copy-Item -LiteralPath $signed -Destination $dest}
  Write-Output ('VERIFIED_OWNER_SIGNED_APK='+$dest)
  Write-Output ('VERIFIED_OWNER_SIGNED_SHA256='+$copyHash)
  if(!$SkipPhoneTransfer){
    $py='D:\AsterionControlRelease\.venv\Scripts\python.exe'
    $transfer='D:\AsterionDownloaderRepair_20261121\tools\native_wifi_owner_apk_transfer.py'
    if(!(Test-Path $py) -or !(Test-Path $transfer)){
      throw 'Signed APK created; existing Asterion Control private Wi-Fi bridge unavailable'
    }
    & $py $transfer $dest 'AsterionDownloader-v1.3.82-OwnerSigned-20a5524b.apk'
    if($LASTEXITCODE -ne 0){throw 'Android MediaStore receipt missing or SHA mismatch'}
    Write-Output 'PHONE_FILE_TRANSFER_VERIFIED=true'
    Write-Output 'PHONE_INSTALLATION_NOT_PERFORMED=true'
  }
} finally {
  $password=$null
  Remove-Item Env:ASTERION_OWNER_1382_SIGNING_PASSWORD -ErrorAction SilentlyContinue
  if(Test-Path $aligned){Remove-Item -LiteralPath $aligned -Force}
}
