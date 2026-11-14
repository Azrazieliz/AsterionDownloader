#Requires -Version 5.1
<#
  Asterion Downloader quarantine release -- Windows security worker.
  Scan incoming untrusted bytes with the OWNED Windows Defender installation.
  Only completed, hash-identical, clean files can leave quarantine.
  A file marked clean by BitTorrent piece hashes or by preliminary file
  extension checks is NEVER sufficient. Missing scanner = reject.
  Use solely on the authenticated PC; never ship a signer/private key in APK.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$QuarantineRoot,
    [Parameter(Mandatory=$true)][string]$ReleaseRoot,
    [Parameter(Mandatory=$true)][string]$RelativeFile,
    [Parameter(Mandatory=$true)][ValidatePattern('^[a-fA-F0-9]{64}$')][string]$ExpectedSha256,
    [string]$ReceiptDirectory = '',
    [switch]$KeepQuarantine
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
function ResolvedDir([string]$path) {
    $full = [IO.Path]::GetFullPath($path).TrimEnd('\','/')
    if (-not (Test-Path -LiteralPath $full -PathType Container)) {
        throw "Required directory missing"
    }
    return $full
}
$quarantine = ResolvedDir $QuarantineRoot
$release = ResolvedDir $ReleaseRoot
if ($RelativeFile -match '(^[\\/]|:|(^|[\\/])\.\.([\\/]|$))') {
    throw 'Unsafe relative filename'
}
$source = [IO.Path]::GetFullPath([IO.Path]::Combine($quarantine,$RelativeFile))
if (-not $source.StartsWith($quarantine+[IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'File escapes quarantine'
}
if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
    throw 'Quarantine file unavailable'
}
$entry=Get-Item -LiteralPath $source -Force
if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
    throw 'Symbolic links and reparse points are prohibited'
}
# Files with reparse-point ancestors are untrusted too.
$ancestor=$entry.Directory
while ($ancestor -and $ancestor.FullName.Length -ge $quarantine.Length) {
    if (($ancestor.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw 'Quarantine ancestors must not be reparse points'
    }
    $ancestor=$ancestor.Parent
}
$before=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($before -ne $ExpectedSha256.ToLowerInvariant()) {
    throw 'Original file hash differs from expected quarantine receipt'
}
$av=Get-MpComputerStatus
if (-not ($av.AntivirusEnabled -and $av.RealTimeProtectionEnabled) -or
    $av.AntivirusSignatureAge -gt 2) {
    throw 'Microsoft Defender unavailable or signatures outdated: file remains quarantined'
}
$root=Join-Path $env:ProgramData 'Microsoft\Windows Defender\Platform'
$version=Get-ChildItem -LiteralPath $root -Directory -ErrorAction SilentlyContinue |
    Sort-Object Name -Descending | Select-Object -First 1
$engine=if ($version) {Join-Path $version.FullName 'MpCmdRun.exe'} else {
    Join-Path $env:ProgramFiles 'Windows Defender\MpCmdRun.exe'
}
if (-not (Test-Path -LiteralPath $engine)) {
    throw 'Microsoft Defender scanning executable unavailable'
}
# -DisableRemediation bypasses exclusions, does not change a suspect file,
# and returns exit 2 on malware detection, prompt, OR scan errors.
# Scan output MUST be checked as well: an exit 0 without a clean result
# is not a release authorization.
$scanOutput=@(& $engine -Scan -ScanType 3 -File $source -DisableRemediation 2>&1)
$scanCode=$LASTEXITCODE
$scanText=($scanOutput | Out-String)
if ($scanCode -ne 0) {
    throw ('Defender scan did not return clean; exit='+$scanCode)
}
if ($scanText -match '(?i)threat.*found|virus found|malware found|infected|error:|failed|warning:') {
    throw 'Defender scan reported an alert or error'
}
$after=(Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant()
if ($after -ne $before) {
    throw 'Quarantine file mutated during security scan'
}
$target=[IO.Path]::GetFullPath([IO.Path]::Combine($release,$RelativeFile))
if (-not $target.StartsWith($release+[IO.Path]::DirectorySeparatorChar,
        [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Release target escapes approved destination'
}
$targetDir=Split-Path -Parent $target
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
# Never overwrite or silently replace a previous, already released asset.
if (Test-Path -LiteralPath $target) { throw 'Released target already exists' }
$stage=$target+'.'+[Guid]::NewGuid().ToString('N')+'.pending'
try {
    [IO.File]::Copy($source,$stage,$false)
    if ((Get-FileHash -LiteralPath $stage -Algorithm SHA256).Hash.ToLowerInvariant() -ne $before) {
        throw 'Released file failed post-copy hash verification'
    }
    [IO.File]::Move($stage,$target)
} finally {
    if (Test-Path -LiteralPath $stage) {Remove-Item -LiteralPath $stage -Force}
}
$receipt=[ordered]@{
    schema='asterion-defender-v1'
    verdict='CLEAN'
    scanner='Microsoft Defender Antivirus'
    engine_version=[string]$av.AMEngineVersion
    signature_version=[string]$av.AntivirusSignatureVersion
    scan_utc=(Get-Date).ToUniversalTime().ToString('o')
    relative_file=$RelativeFile.Replace('\','/')
    sha256=$before
    bytes=(Get-Item -LiteralPath $source).Length
    released=$true
}
if (-not [string]::IsNullOrWhiteSpace($ReceiptDirectory)) {
    $outputRoot=ResolvedDir $ReceiptDirectory
    $file=[IO.Path]::Combine($outputRoot,$before+'.scan.json')
    [IO.File]::WriteAllText($file,($receipt|ConvertTo-Json -Depth 5),
        [Text.Encoding]::UTF8)
}
# Explicit successful scanner verdict and byte-identical published copy:
# clean-up is now safe. No indefinitely accumulating duplicate quarantine
# file. A scan error leaves ALL originals where they were.
if (-not $KeepQuarantine) {
    $confirmed=(Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($confirmed -ne $before) { throw 'Release hash changed before quarantine cleanup' }
    Remove-Item -LiteralPath $source -Force
    $receipt['quarantine_removed']=(-not (Test-Path -LiteralPath $source))
    if (-not [string]::IsNullOrWhiteSpace($ReceiptDirectory)) {
        [IO.File]::WriteAllText($file,($receipt|ConvertTo-Json -Depth 5),
            [Text.Encoding]::UTF8)
    }
}
Write-Output ($receipt|ConvertTo-Json -Depth 5 -Compress)
