param(
    [string]$Project = 'D:\AsterionDownloader',
    [string]$SignerDirectory = 'D:\AsterionControlRelease\signing\downloader-acceptance'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$pkcs12 = Join-Path $SignerDirectory 'asterion-downloader-acceptance.p12'
$protected = Join-Path $SignerDirectory 'password.dpapi'
if (!(Test-Path $pkcs12) -or !(Test-Path $protected)) {
    throw 'Retained owner signer is missing. Do NOT substitute a debug key.'
}
Add-Type -AssemblyName System.Security
$private = [System.Security.Cryptography.ProtectedData]::Unprotect(
    [System.IO.File]::ReadAllBytes($protected), $null,
    [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
$password = [System.Text.Encoding]::UTF8.GetString($private)
[Array]::Clear($private, 0, $private.Length)
if ([string]::IsNullOrWhiteSpace($password)) { throw 'Owner signing secret was empty' }
try {
    $keytool = (Get-Command 'keytool.exe' -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (!$keytool -and $env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
        if (Test-Path $candidate) { $keytool = $candidate }
    }
    if (!$keytool) { throw 'Java keytool is unavailable; original signer cannot be verified' }
    $info = @(& $keytool -list -keystore $pkcs12 -storetype PKCS12 -storepass $password 2>&1)
    if ($LASTEXITCODE -ne 0) { throw 'Retained owner PKCS12 signer did not unlock' }
    $aliases = @($info | Where-Object { $_ -match ',\s.*PrivateKeyEntry' } |
        ForEach-Object { ($_ -split ',',2)[0].Trim() })
    if ($aliases.Count -ne 1 -or [string]::IsNullOrWhiteSpace($aliases[0])) {
        throw 'Expected exactly one private signing key in retained owner keystore'
    }
    $env:ASTERION_PREVIEW_KEYSTORE = $pkcs12
    $env:ASTERION_PREVIEW_STORE_PASSWORD = $password
    $env:ASTERION_PREVIEW_KEY_ALIAS = $aliases[0]
    $env:ASTERION_PREVIEW_KEY_PASSWORD = $password
    Push-Location $Project
    try {
        $head = (& git rev-parse HEAD).Trim()
        if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') {
            throw 'Working tree does not point to a known GitHub commit'
        }
        Write-Output ('SOURCE_COMMIT=' + $head)
        & (Join-Path $Project 'gradlew.bat') --no-daemon :app:assembleAcceptance
        if ($LASTEXITCODE -ne 0) { throw ('Gradle acceptance build failed with exit ' + $LASTEXITCODE) }
        $apk = Join-Path $Project 'app\build\outputs\apk\acceptance\app-acceptance.apk'
        if (!(Test-Path $apk)) { throw 'Acceptance APK output missing' }
        $digest = (Get-FileHash $apk -Algorithm SHA256).Hash
        $bytes = (Get-Item $apk).Length
        if ($bytes -lt 1000000) { throw 'APK artifact is implausibly small' }
        Write-Output ('SIGNED_APK=' + $apk)
        Write-Output ('SIGNED_BYTES=' + $bytes)
        Write-Output ('SIGNED_SHA256=' + $digest)
        Write-Output ('SIGNING_KEY_ALIAS=' + $aliases[0])
        Write-Output 'SIGNING_ORIGIN=RETAINED_WINDOWS_OWNER_PKCS12'
    }
    finally { Pop-Location }
}
finally {
    $password = $null
    foreach ($name in @('ASTERION_PREVIEW_KEYSTORE','ASTERION_PREVIEW_STORE_PASSWORD',
                         'ASTERION_PREVIEW_KEY_ALIAS','ASTERION_PREVIEW_KEY_PASSWORD')) {
        Remove-Item ('Env:'+$name) -ErrorAction SilentlyContinue
    }
}
