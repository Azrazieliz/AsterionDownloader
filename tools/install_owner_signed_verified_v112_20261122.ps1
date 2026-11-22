# Install the already built, tested v112 APK without another build.
# Compare its signer against the actual installed APK, not a remembered fingerprint.
param([string]$Project='D:\AsterionDownloaderWebViewRepair20261122')
$ErrorActionPreference='Continue'
$package='com.azrael.pixivdumpsync.acceptance'
$source='b1a25acef956173a38329caeb427159094a6bd40'
$expectedApkSha='5D3C691724B79543E01B51971D9239304C64E12191B24F46D5F854ED3548EA5D'
$log=Join-Path $Project 'owner_signed_release112_20261122.log'
$apk=Join-Path $Project 'app\build\outputs\apk\acceptance\app-acceptance.apk'
function Abort([string]$why){Write-Output ('INSTALL112_BLOCKED='+$why);exit 22}
if(!(Test-Path $apk) -or !(Test-Path $log)){Abort 'Signed release artifact missing'}
$receipt=Get-Content $log -Raw
if(!$receipt.Contains('SOURCE_COMMIT='+$source) -or
   !$receipt.Contains('SIGNING_ORIGIN=RETAINED_WINDOWS_OWNER_PKCS12') -or
   !$receipt.Contains('BUILD SUCCESSFUL')){Abort 'Signed receipt source and key not established'}
$sha=(Get-FileHash $apk -Algorithm SHA256).Hash
if($sha -ne $expectedApkSha){Abort 'APK changed since validated build'}
$roots=@($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,(Join-Path $env:LOCALAPPDATA 'Android\Sdk'))|Where-Object{$_ -and (Test-Path $_)}|Select-Object -Unique
$adb=(Get-Command adb.exe -ErrorAction SilentlyContinue|Select-Object -First 1).Source
if(!$adb){foreach($root in $roots){$candidate=Join-Path $root 'platform-tools\adb.exe';if(Test-Path $candidate){$adb=$candidate;break}}}
if(!$adb){Abort 'ADB unavailable'}
$devices=@(& $adb devices -l|Select-String '^([^\s]+)\s+device(?:\s|$)'|ForEach-Object{$_.Matches[0].Groups[1].Value})
if($devices.Count -ne 1){Abort ('Authorized phone count='+$devices.Count)}
$serial=$devices[0]
$installed=(& $adb -s $serial shell dumpsys package $package) -join [Environment]::NewLine
$version=[regex]::Match($installed,'versionCode=(\d+)')
if(!$version.Success){Abort 'Installed acceptance package not found'}
if([int]$version.Groups[1].Value -gt 112){Abort 'Newer version installed; cannot downgrade'}
Write-Output ('INSTALLED_BEFORE='+$version.Groups[1].Value)
$base=@(& $adb -s $serial shell pm path $package|Where-Object{$_ -match '^package:.*base\.apk'}|Select-Object -First 1)
if($base.Count -ne 1){Abort 'Could not identify installed base APK for signer comparison'}
$oldFile=Join-Path $Project 'installed_v111_certificate_baseline.apk'
& $adb -s $serial pull $base[0].Substring(8).Trim() $oldFile
if($LASTEXITCODE -ne 0 -or !(Test-Path $oldFile)){Abort 'Cannot read installed signer from base APK'}
$signer=$null;$aapt=$null
foreach($root in $roots){
 $tools=Join-Path $root 'build-tools'
 if(!(Test-Path $tools)){continue}
 foreach($d in @(Get-ChildItem $tools -Directory|Sort-Object Name -Descending)){
  if(!$signer -and (Test-Path (Join-Path $d.FullName 'apksigner.bat'))){$signer=Join-Path $d.FullName 'apksigner.bat'}
  if(!$aapt -and (Test-Path (Join-Path $d.FullName 'aapt.exe'))){$aapt=Join-Path $d.FullName 'aapt.exe'}
 }
}
if(!$signer -or !$aapt){Abort 'Android verifier not found'}
$oldSign=(& $signer verify --print-certs $oldFile) -join [Environment]::NewLine
if($LASTEXITCODE -ne 0){Abort 'Installed APK signature invalid'}
$newSign=(& $signer verify --print-certs $apk) -join [Environment]::NewLine
if($LASTEXITCODE -ne 0){Abort 'Update APK signature invalid'}
$regex='(?i)(?:V2 Signer|Signer #1):? certificate SHA-256 digest:\s*([0-9a-f]{64})'
$oldCert=[regex]::Match($oldSign,$regex).Groups[1].Value.ToUpperInvariant()
$newCert=[regex]::Match($newSign,$regex).Groups[1].Value.ToUpperInvariant()
Write-Output ('INSTALLED_CERT_SHA256='+$oldCert)
Write-Output ('UPDATE_CERT_SHA256='+$newCert)
if($oldCert.Length -ne 64 -or $oldCert -ne $newCert){Abort 'Certificate mismatch; cannot preserve app data via in-place update'}
$badging=@(& $aapt dump badging $apk)
$ident=@($badging|Where-Object{$_ -match '^package:'}|Select-Object -First 1)
if($LASTEXITCODE -ne 0 -or $ident.Count -ne 1 -or
   $ident[0] -notmatch "name='com.azrael.pixivdumpsync.acceptance'" -or
   $ident[0] -notmatch "versionCode='112'"){Abort 'Wrong APK identity/version'}
Write-Output ('APK_VERIFIED_SHA256='+$sha)
Write-Output 'ANDROID_INSTALL_R_IN_PROGRESS'
$receiptInstall=@(& $adb -s $serial install -r $apk 2>&1)
$code=$LASTEXITCODE
$receiptInstall|ForEach-Object{Write-Output ('INSTALL_RESULT='+$_)}
if($code -ne 0 -or !(($receiptInstall -join [Environment]::NewLine) -match '(?m)Success')){Abort ('Install failed code '+$code)}
$after=(& $adb -s $serial shell dumpsys package $package) -join [Environment]::NewLine
$check=[regex]::Match($after,'versionCode=(\d+)')
if(!$check.Success -or $check.Groups[1].Value -ne '112'){Abort 'Installation returned success but versionCode is not 112'}
Write-Output ('INSTALLED_VERIFIED=TRUE VERSION=112 PACKAGE='+$package+' CERT_SHA256='+$newCert+' APK_SHA256='+$sha)
exit 0
