param(
  [Parameter(Mandatory=$true)][ValidatePattern('^https://')][string]$Url,
  [int]$Limit=20
)
$ErrorActionPreference='Stop'
$req=[System.Net.HttpWebRequest]::Create($Url)
$req.AllowAutoRedirect=$true
$req.MaximumAutomaticRedirections=5
$req.Timeout=20000
$req.UserAgent='Mozilla/5.0 (Linux; Android 17; Mobile)'
try { $resp=$req.GetResponse() }
catch [System.Net.WebException] {
  $resp=$_.Exception.Response
  if($null -eq $resp) { Write-Output ('NETWORK_ERROR '+$_.Exception.Message); exit 2 }
}
try {
  $final=$resp.ResponseUri.AbsoluteUri
  Write-Output ('STATUS '+[int]$resp.StatusCode)
  Write-Output ('ORIGINAL_HOST '+([uri]$Url).Host)
  Write-Output ('FINAL_HOST '+([uri]$final).Host)
  Write-Output ('FINAL_PATH '+([uri]$final).AbsolutePath)
  $stream=$resp.GetResponseStream()
  $reader=[IO.StreamReader]::new($stream)
  $html=$reader.ReadToEnd()
  $reader.Close()
  Write-Output ('HTML_BYTES '+$html.Length)
  $links=[regex]::Matches($html,'(?is)<a\b[^>]*?href\s*=\s*["'']([^"'']+)["'']')
  $paths=New-Object 'System.Collections.Generic.HashSet[string]'
  foreach($link in $links) {
    $part=[System.Net.WebUtility]::HtmlDecode($link.Groups[1].Value)
    $resolved=$null
    if(-not [uri]::TryCreate([uri]$final,$part,[ref]$resolved)){continue}
    if($resolved.Scheme -ne 'https'){continue}
    if($resolved.Host -ne ([uri]$final).Host){continue}
    $path=$resolved.AbsolutePath
    if($path -notmatch '(?i)(video|watch|porn|hentai|jav|movie|[0-9]{5,})'){continue}
    if($path.Length -lt 4){continue}
    [void]$paths.Add($path)
    if($paths.Count -ge $Limit){break}
  }
  Write-Output ('LINK_CANDIDATES '+$paths.Count)
  foreach($path in $paths) { Write-Output ('PATH '+$path.Substring(0,[Math]::Min(115,$path.Length))) }
}
finally { if($resp){$resp.Dispose()} }
