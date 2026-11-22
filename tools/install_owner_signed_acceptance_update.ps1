# Reusable verified in-place owner-signed Downloader acceptance update.
# Checks the exact installed package signer and source/build receipt.
param(
 [Parameter(Mandatory=$true)][ValidateRange(113,999999)][int]$ExpectedCode,
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-fA-F]{40}$')][string]$SourceCommit,
 [string]$Project='D:\AsterionDownloaderWebViewRepair20261122',
 [string]$Package='com.azrael.pixivdumpsync.acceptance'
)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
$apk=Join-Path $Project 'app\build\outputs\apk\acceptance\app-acceptance.apk'
$buildLog=Join-Path $Project ("owner_signed_javguru_lu_v"+$ExpectedCode+"_20261122.log")
$receipt=Join-Path $Project ("verified_install_"+$ExpectedCode+".json")
function Abort([string]$reason){
 @{'status'='BLOCKED';'versionCode'=$ExpectedCode;'reason'=$reason} |
 ConvertTo-Json -Compress | Set-Content $receipt -Encoding UTF8
 throw ('UPDATE_NOT_INSTALLED: '+$reason)
}
if(!(Test-Path $apk) -or !(Test-Path $buildLog)){Abort 'Owner-signed APK or receipt missing'}
$log=Get-Content $buildLog -Raw
if(!$log.Contains('SOURCE_COMMIT='+$SourceCommit) -or
 !$log.Contains('SIGNING_ORIGIN=RETAINED_WINDOWS_OWNER_PKCS12') -or
 !$log.Contains('BUILD SUCCESSFUL')){Abort 'Wrong signed source/build'}
$sdk=@($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,
 (Join-Path $env:LOCALAPPDATA 'Android\Sdk')) |
 Where-Object{$_ -and (Test-Path $_)} | Select-Object -Unique
$adb=(Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if(!$adb){foreach($root in $sdk){
 $candidate=Join-Path $root 'platform-tools\adb.exe'
 if(Test-Path $candidate){$adb=$candidate;break}
}}
if(!$adb){Abort 'ADB not available'}
$devices=@(& $adb devices -l |
 Select-String '^([^\s]+)\s+device(?:\s|$)' |
 ForEach-Object{$_.Matches[0].Groups[1].Value})
if($devices.Count -ne 1){Abort ('Expected one phone; found '+$devices.Count)}
$serial=$devices[0]
$before=(& $adb -s $serial shell dumpsys package $Package) -join [Environment]::NewLine
$old=[regex]::Match($before,'versionCode=(\d+)')
if(!$old.Success){Abort 'Acceptance app not installed'}
if([int]$old.Groups[1].Value -gt $ExpectedCode){Abort 'Downgrade blocked'}
$signer=$null;$aapt=$null
foreach($root in $sdk){
 $folder=Join-Path $root 'build-tools'
 if(!(Test-Path $folder)){continue}
 foreach($dir in @(Get-ChildItem $folder -Directory|Sort-Object Name -Descending)){
  if(!$signer -and (Test-Path (Join-Path $dir.FullName 'apksigner.bat'))){
   $signer=Join-Path $dir.FullName 'apksigner.bat'}
  if(!$aapt -and (Test-Path (Join-Path $dir.FullName 'aapt.exe'))){
   $aapt=Join-Path $dir.FullName 'aapt.exe'}
 }
}
if(!$signer -or !$aapt){Abort 'Android APK verification tools missing'}
$badging=@(& $aapt dump badging $apk)
if($LASTEXITCODE -ne 0){Abort 'Cannot inspect APK identity'}
$ident=@($badging | Where-Object{$_ -match '^package:'} | Select-Object -First 1)
if($ident.Count -ne 1 -or
 !$ident[0].Contains("name='$Package'") -or
 !$ident[0].Contains("versionCode='$ExpectedCode'")){
 Abort 'APK package or version mismatch'}
$verified=@(& $signer verify --print-certs $apk)
if($LASTEXITCODE -ne 0){Abort 'New APK signature invalid'}
$pattern='(?i)(?:V2 Signer|Signer #1):? certificate SHA-256 digest:\s*([0-9a-f]{64})'
$newCert=[regex]::Match(($verified -join [Environment]::NewLine),$pattern).Groups[1].Value.ToUpperInvariant()
if($newCert.Length -ne 64){Abort 'Cannot identify signed APK certificate'}
$paths=@(& $adb -s $serial shell pm path $Package)
$base=@($paths | Where-Object{$_ -match '^package:.*base\.apk'} | Select-Object -First 1)
if($base.Count -ne 1){Abort 'Cannot identify installed base APK'}
$tmp=Join-Path $env:TEMP ("asterion-cert-"+[guid]::NewGuid().ToString('N')+'.apk')
try{
 # ADB prints successful "1 file pulled" progress on stderr. Under
 # ErrorActionPreference=Stop PowerShell mistakenly raises NativeCommandError.
 # Judge the actual process exit code and copied APK bytes instead.
 $oldPreference=$ErrorActionPreference
 try{
  $ErrorActionPreference='Continue'
  & $adb -s $serial pull $base[0].Substring(8).Trim() $tmp *> $null
  $pullCode=$LASTEXITCODE
 }finally{$ErrorActionPreference=$oldPreference}
 if($pullCode -ne 0 -or !(Test-Path $tmp)){Abort 'Installed APK not readable'}
 $oldVerify=@(& $signer verify --print-certs $tmp)
 if($LASTEXITCODE -ne 0){Abort 'Installed APK signature invalid'}
 $oldCert=[regex]::Match(($oldVerify -join [Environment]::NewLine),$pattern).Groups[1].Value.ToUpperInvariant()
 if($oldCert.Length -ne 64 -or $oldCert -ne $newCert){Abort 'Signature mismatch; preserve existing installation'}
}finally{Remove-Item $tmp -Force -ErrorAction SilentlyContinue}
$hash=(Get-FileHash $apk -Algorithm SHA256).Hash
Write-Output ('OWNER_CERTIFICATE_MATCH=TRUE OLD_VERSION='+$old.Groups[1].Value+
 ' NEW_VERSION='+$ExpectedCode+' APK_SHA256='+$hash)
# Installed-app updates use an exit-code checked ADB operation; progress
# text on stderr is not an installation failure.
$oldPreference=$ErrorActionPreference
try{
 $ErrorActionPreference='Continue'
 $install=@(& $adb -s $serial install -r $apk 2>&1)
 $exitCode=$LASTEXITCODE
}finally{$ErrorActionPreference=$oldPreference}
$install | ForEach-Object{Write-Output ('ANDROID_INSTALL='+$_)}
if($exitCode -ne 0 -or !(($install -join [Environment]::NewLine) -match '(?m)^Success')){
 Abort ('Installer failed exit '+$exitCode)}
$after=(& $adb -s $serial shell dumpsys package $Package) -join [Environment]::NewLine
$version=[regex]::Match($after,'versionCode=(\d+)')
if(!$version.Success -or [int]$version.Groups[1].Value -ne $ExpectedCode){
 Abort 'Post-install installed version mismatch'}
@{'status'='INSTALLED_VERIFIED';'versionCode'=$ExpectedCode;
 'package'=$Package;'sha256'=$hash;'certSha256'=$newCert;
 'sourceCommit'=$SourceCommit;'preservedData'=$true;
 'verifiedAt'=(Get-Date).ToString('o')} |
 ConvertTo-Json -Compress | Set-Content $receipt -Encoding UTF8
Write-Output ('INSTALLED_VERIFIED=TRUE VERSION='+$ExpectedCode+
 ' PACKAGE='+$Package+' SHA256='+$hash)
