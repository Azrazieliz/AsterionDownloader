param(
  [Parameter(Mandatory=$true)][ValidatePattern('^https://')][string]$Listing,
  [Parameter(Mandatory=$true)][string]$VideoPathRegex,
  [int]$MaxSeconds=180,
  [switch]$DirectSource
)
$ErrorActionPreference='Stop'
$here=Split-Path -Parent $MyInvocation.MyCommand.Path
$python='D:\Tools\Python311\python.exe'
$env:PYTHONPATH=Join-Path $env:TEMP 'asterion_site_test_ytdlp'
if(-not (Test-Path (Join-Path $here 'site_original_smoke.py'))){
  throw 'Missing site original tester'
}
$response=Invoke-WebRequest -Uri $Listing -UseBasicParsing -TimeoutSec 20 -Headers @{'User-Agent'='Mozilla/5.0 (Linux; Android 17; Mobile)'}
$root=[uri]$response.BaseResponse.ResponseUri.AbsoluteUri
$rx=[regex]'(?is)<a\b[^>]*?href\s*=\s*["'']([^"'']+)["'']'
$candidate=$null
foreach($match in $rx.Matches($response.Content)){
  $link=[System.Net.WebUtility]::HtmlDecode($match.Groups[1].Value)
  $u=$null
  if(-not [uri]::TryCreate($root,$link,[ref]$u)){continue}
  if($u.Scheme -ne 'https' -or $u.Host -ne $root.Host){continue}
  if($u.AbsolutePath -notmatch $VideoPathRegex){continue}
  $candidate=$u
  break
}
Write-Output ('ORIGIN_HOST '+$root.Host)
if($null -eq $candidate){
  Write-Output ('DISCOVERY_NO_MATCH '+$VideoPathRegex)
  exit 2
}
Write-Output ('VIDEO_PATH_LENGTH '+$candidate.AbsolutePath.Length)
Write-Output ('VIDEO_PATH_SEGMENTS '+$candidate.AbsolutePath.Trim('/').Split('/').Count)
if($DirectSource){
  & $python (Join-Path $here 'site_direct_source_receipt.py') --url $candidate.AbsoluteUri --max-bytes 31457280
}else{
  & $python (Join-Path $here 'site_original_smoke.py') --url $candidate.AbsoluteUri --max-seconds $MaxSeconds
}
exit $LASTEXITCODE
