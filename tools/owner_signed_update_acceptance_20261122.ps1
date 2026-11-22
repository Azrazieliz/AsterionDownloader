# Validated owner-signed in-place update, no uninstall, no data clearing.
param([string]$Project = 'D:\AsterionDownloaderWebViewRepair20261122')
$ErrorActionPreference='Continue'
$package='com.azrael.pixivdumpsync.acceptance'
$expectedCert='8914AF1B2713E1D7E93C52ABFDDF11CBC6BBA8D7057906161185A4216DCC0C19'
$testLog=Join-Path $Project 'validation_release112_20261122.log'
$buildLog=Join-Path $Project 'owner_signed_release112_20261122.log'
$result=Join-Path $Project 'owner_update112_result_20261122.json'
function Phase([string]$step,[string]$state,[string]$detail=''){
  @{step=$step;status=$state;detail=$detail;at=(Get-Date).ToString('o')} | ConvertTo-Json -Compress | Set-Content $result -Encoding UTF8
  Write-Output ('RELEASE112: '+$step+' '+$state+' '+$detail)
}
function Abort([string]$step,[string]$why){ Phase $step 'BLOCKED' $why; exit 22 }
Phase 'preflight' 'RUNNING'
if(!(Test-Path $Project)){Abort 'preflight' 'Canonical worktree missing'}
$head=(& git -C $Project rev-parse HEAD).Trim()
$gradleConfig=Get-Content (Join-Path $Project 'app\build.gradle.kts') -Raw
if($head -notmatch '^[0-9a-f]{40}$' -or $gradleConfig -notmatch 'versionCode\s*=\s*112' -or
   $gradleConfig -notmatch 'versionName\s*=\s*"1.3.83"' -or
   $gradleConfig -notmatch 'applicationIdSuffix\s*=\s*"\.acceptance"'){
   Abort 'preflight' 'Wrong source, package identity or version'
}
$dirty=@(& git -C $Project status --porcelain --untracked-files=no)
if($LASTEXITCODE -ne 0 -or $dirty.Count -gt 0){Abort 'preflight' 'Tracked source modified'}
$roots=@($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT,(Join-Path $env:LOCALAPPDATA 'Android\Sdk')) | Where-Object{$_ -and (Test-Path $_)} | Select-Object -Unique
$adb=(Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if(!$adb){foreach($root in $roots){$candidate=Join-Path $root 'platform-tools\adb.exe';if(Test-Path $candidate){$adb=$candidate;break}}}
if(!$adb){Abort 'preflight' 'ADB unavailable'}
$devices=@(& $adb devices -l | Select-String '^([^\s]+)\s+device(?:\s|$)' | ForEach-Object{$_.Matches[0].Groups[1].Value})
if($devices.Count -ne 1){Abort 'preflight' ('Expected exactly one phone, got '+$devices.Count)}
$serial=$devices[0]
$installed=(& $adb -s $serial shell dumpsys package $package) -join [Environment]::NewLine
$old=[regex]::Match($installed,'versionCode=(\d+)')
if(!$old.Success){Abort 'preflight' 'Existing acceptance app not detected'}
if([int]$old.Groups[1].Value -gt 112){Abort 'preflight' 'Cannot downgrade newer installed app'}
Write-Output ('RELEASE112: installed_before='+$old.Groups[1].Value)
$dist=Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.13-bin'
$gradle=(Get-ChildItem $dist -Recurse -Filter gradle.bat -File -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
if(!$gradle){Abort 'tests' 'Gradle 8.13 missing'}
Phase 'tests' 'RUNNING' $head
Push-Location $Project
try{& $gradle --offline --no-daemon --console=plain :app:verifyFrozenVideoProviders :app:testAcceptanceUnitTest *> $testLog; $testExit=$LASTEXITCODE}
finally{Pop-Location}
$tests=Get-Content $testLog -ErrorAction SilentlyContinue
$tests | Select-Object -Last 12 | ForEach-Object{Write-Output $_}
if($testExit -ne 0 -or !(($tests -join [Environment]::NewLine).Contains('BUILD SUCCESSFUL'))){Abort 'tests' ('Acceptance tests failed, code '+$testExit)}
Phase 'build' 'RUNNING' 'Original retained PKCS12 key'
try{& (Join-Path $Project 'tools\build_owner_signed_acceptance.ps1') -Project $Project *> $buildLog; $buildOk=$?}
catch{$buildOk=$false;Write-Output ('SIGNER_EXCEPTION '+$_.Exception.Message)}
$build=Get-Content $buildLog -ErrorAction SilentlyContinue
$build | Select-Object -Last 14 | ForEach-Object{Write-Output $_}
if(!$buildOk -or !(($build -join [Environment]::NewLine).Contains('BUILD SUCCESSFUL')) -or
   !(($build -join [Environment]::NewLine).Contains('SIGNING_ORIGIN=RETAINED_WINDOWS_OWNER_PKCS12'))){Abort 'build' 'Signed assembly failed'}
$apk=Join-Path $Project 'app\build\outputs\apk\acceptance\app-acceptance.apk'
if(!(Test-Path $apk)){Abort 'signing' 'APK missing'}
$signer=$null;$aapt=$null
foreach($root in $roots){
 $bt=Join-Path $root 'build-tools'
 if(!(Test-Path $bt)){continue}
 foreach($dir in @(Get-ChildItem $bt -Directory | Sort-Object Name -Descending)){
  if(!$signer -and (Test-Path (Join-Path $dir.FullName 'apksigner.bat'))){$signer=Join-Path $dir.FullName 'apksigner.bat'}
  if(!$aapt -and (Test-Path (Join-Path $dir.FullName 'aapt.exe'))){$aapt=Join-Path $dir.FullName 'aapt.exe'}
 }
}
if(!$signer -or !$aapt){Abort 'signing' 'Android signing verification tools missing'}
Phase 'signing' 'VERIFYING'
$certInfo=(& $signer verify --print-certs $apk) -join [Environment]::NewLine
if($LASTEXITCODE -ne 0){Abort 'signing' 'APK signature invalid'}
$cert=[regex]::Match($certInfo,'(?i)Signer #1 certificate SHA-256 digest:\s*([0-9a-f]{64})')
if(!$cert.Success -or $cert.Groups[1].Value.ToUpperInvariant() -ne $expectedCert){Abort 'signing' 'Signer does not match installed owner'}
$badging=@(& $aapt dump badging $apk)
$identity=@($badging | Where-Object{$_ -match '^package:'} | Select-Object -First 1)
if($LASTEXITCODE -ne 0 -or $identity.Count -ne 1 -or
   $identity[0] -notmatch "name='com.azrael.pixivdumpsync.acceptance'" -or
   $identity[0] -notmatch "versionCode='112'"){Abort 'signing' 'APK package or version mismatch'}
$hash=(Get-FileHash $apk -Algorithm SHA256).Hash
Write-Output ('RELEASE112: VERIFIED_APK sha256='+$hash+' bytes='+(Get-Item $apk).Length)
Phase 'installation' 'RUNNING' 'Using adb install -r, no uninstall or data clearing'
$install=@(& $adb -s $serial install -r $apk 2>&1)
$code=$LASTEXITCODE
$install | ForEach-Object{Write-Output ('INSTALL: '+$_)}
if($code -ne 0 -or !(($install -join [Environment]::NewLine) -match '(?m)Success')){Abort 'installation' ('Android installer failed '+$code)}
$after=(& $adb -s $serial shell dumpsys package $package) -join [Environment]::NewLine
$version=[regex]::Match($after,'versionCode=(\d+)')
if(!$version.Success -or [int]$version.Groups[1].Value -ne 112){Abort 'verification' 'Installed package version not 112'}
$path=(& $adb -s $serial shell pm path $package) -join [Environment]::NewLine
if($path -notmatch 'package:'){Abort 'verification' 'Installed package path missing'}
Phase 'verification' 'COMPLETE' ('versionCode=112 package='+$package+' source='+$head+' sha256='+$hash)
Write-Output ('RELEASE112: INSTALLED_VERIFIED versionCode=112 package='+$package+' sha256='+$hash)
exit 0
