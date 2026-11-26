# Isolated, owner-authorized Android test lane. No production APK/signing/phone installation.
param([Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{40}$')][string]$ExpectedCommit)
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$w='D:\AsterionDownloaderReleaseVerify_20261120'
$r='D:\AsterionDownloaderEmulator'
$s=Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$g=Join-Path $env:USERPROFILE '.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat'
$adb=Join-Path $s 'platform-tools\adb.exe'
$emu=Join-Path $s 'emulator\emulator.exe'
$avd='AsterionDownloaderTest30'
$serial='emulator-5580'
$log=Join-Path $r 'test-run'
if((Get-PSDrive D).Free -lt 10GB){throw 'Insufficient D: space for isolated test executable and emulator'}
foreach($p in @($w,$g,$adb,$emu,"$r\avd\$avd.ini","$r\sdk\system-images\android-30\default\x86_64\system.img")){
    if(!(Test-Path -LiteralPath $p)){throw "Required test resource missing: $p"}
}
if(@(& git -C $w status --porcelain --untracked-files=no).Count -ne 0){throw 'Existing worktree contains tracked changes'}
if((& git -C $w rev-parse HEAD).Trim() -ne $ExpectedCommit){
    & git -C $w fetch origin repair/downloader-global-search-20261121 --quiet
    if($LASTEXITCODE -ne 0){throw 'Fetch failed'}
    & git -C $w checkout --detach $ExpectedCommit --quiet
    if($LASTEXITCODE -ne 0){throw 'Checkout failed'}
}
if((& git -C $w rev-parse HEAD).Trim() -ne $ExpectedCommit){throw 'Source identity mismatch'}
New-Item -ItemType Directory -Force $log | Out-Null
$env:TEMP="$r\sdk\temp";$env:TMP=$env:TEMP
$env:ANDROID_HOME=$s;$env:ANDROID_SDK_ROOT=$s
$wheel=Join-Path $w 'app\src\main\assets\yt_dlp-2026.10.26-py3-none-any.whl'
if(!(Test-Path $wheel) -or (Get-FileHash -LiteralPath $wheel -Algorithm SHA256).Hash.ToLowerInvariant() -ne 'a05c5b5d8562f7e8a62f5334348b406af4930d56d3a65f8021d14af0e22339ae'){throw 'Pinned test extractor missing or changed'}
Write-Output "SOURCE_SHA=$ExpectedCommit"
# Do not assemble the temporary test executables unless the source gates pass.
$ErrorActionPreference='Continue'
& $g -p $w :app:verifyFrozenVideoProviders :app:testPilotUnitTest :app:testAcceptanceUnitTest --offline --no-daemon --console=plain *> "$log\preflight.log"
$rc=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($rc -ne 0){Get-Content "$log\preflight.log" -Tail 40;throw 'Source regression gate failed'}
Write-Output 'SOURCE_PREFLIGHT_PASS'
# Never invoke assembleAcceptance. Only the distinct phonepilot test package.
$ErrorActionPreference='Continue'
& $g -p $w :app:assemblePhonepilot :app:assemblePhonepilotAndroidTest --offline --no-daemon --console=plain *> "$log\test-assembly.log"
$rc=$LASTEXITCODE
$ErrorActionPreference='Stop'
if($rc -ne 0){Get-Content "$log\test-assembly.log" -Tail 40;throw 'Temporary test assembly failed'}
$apk="$w\app\build\outputs\apk\phonepilot\app-phonepilot.apk"
$test="$w\app\build\outputs\apk\androidTest\phonepilot\app-phonepilot-androidTest.apk"
if(!(Test-Path $apk) -or !(Test-Path $test)){throw 'Temporary test executables missing'}
# Explicit serial only; never send commands to a physical phone.
if((& $adb devices | Out-String) -match '(?m)^emulator-5580\s'){throw 'Reserved emulator port is already occupied'}
if(Get-NetTCPConnection -LocalPort 5580,5581 -State Listen -ErrorAction SilentlyContinue){throw 'Reserved emulator ports already occupied'}
$env:ANDROID_HOME="$r\sdk";$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
$env:ANDROID_AVD_HOME="$r\avd";$env:ANDROID_USER_HOME=$r
$p=Start-Process -FilePath $emu -ArgumentList @('-avd',$avd,'-port','5580','-no-window','-no-snapshot','-noaudio','-no-boot-anim','-gpu','swiftshader_indirect','-memory','3072','-cores','2','-accel','on','-sysdir',"$r\sdk\system-images\android-30\default\x86_64") -PassThru -RedirectStandardOutput "$log\emulator.stdout.log" -RedirectStandardError "$log\emulator.stderr.log"
try{
    $deadline=(Get-Date).AddMinutes(5)
    $booted=$false
    while((Get-Date) -lt $deadline){
        if($p.HasExited){Get-Content "$log\emulator.stderr.log" -Tail 30;throw 'Emulator exited before boot'}
        $ErrorActionPreference='Continue'
        $boot=(& $adb -s $serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        $ErrorActionPreference='Stop'
        if($boot -eq '1'){$booted=$true;break}
        Start-Sleep -Seconds 3
    }
    if(!$booted){throw 'Isolated emulator boot deadline exceeded'}
    if((& $adb -s $serial shell getprop ro.kernel.qemu | Out-String).Trim() -ne '1'){throw 'Refusing non-emulator target'}
    Write-Output 'EMULATOR_BOOT_PASS'
    & $adb -s $serial install -r $apk
    if($LASTEXITCODE -ne 0){throw 'Test app installation failed'}
    & $adb -s $serial install -r $test
    if($LASTEXITCODE -ne 0){throw 'Test instrumentation installation failed'}
    & $adb -s $serial logcat -c
    $i=Start-Process -FilePath $adb -ArgumentList @('-s',$serial,'shell','am','instrument','-w','-r','com.azrael.pixivdumpsync.testphone1111.test/com.azrael.pixivdumpsync.DeviceTransferSimulation') -PassThru -RedirectStandardOutput "$log\instrumentation.log" -RedirectStandardError "$log\instrumentation.stderr.log"
    if(!$i.WaitForExit(360000)){Stop-Process -Id $i.Id -Force;throw 'Instrumentation deadline exceeded'}
    & $adb -s $serial logcat -b crash -d -v threadtime > "$log\crashes.log"
    $body=Get-Content "$log\instrumentation.log" -Raw
    $body
    if($i.ExitCode -ne 0 -or $body -match 'Process crashed|INSTRUMENTATION_FAILED' -or (Get-Content "$log\crashes.log" -Raw) -match 'FATAL EXCEPTION'){throw 'Runtime instrumentation failed'}
    foreach($marker in @('NATIVE_OK','PARALLEL_NATIVE_OK','PYTHON_RUNTIME_OK','EMBEDDED_OK','PASS')){
        if(!$body.Contains("AST_SIM: $marker")){throw "Missing runtime receipt: $marker"}
    }
    Write-Output 'CONTROLLED_ANDROID_TRANSFER_SIMULATION_PASS'
}finally{
    if(!$p.HasExited){& $adb -s $serial emu kill 2>$null | Out-Null}
}
