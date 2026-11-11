param(
 [Parameter(Mandatory=$true)][string]$Apk,
 [Parameter(Mandatory=$true)][string]$ShaFile,
 [Parameter(Mandatory=$true)][ValidatePattern('^[0-9a-f]{40}$')][string]$Commit
)
$ErrorActionPreference='Stop'
$repo='Azrazieliz/AsterionDownloader'
$tag='asterion-downloader-v1.3.51-acceptance-'+$Commit.Substring(0,10)
$name='Asterion-Downloader-v1.3.51-Acceptance.apk'
$shaName='Asterion-Downloader-v1.3.51-Acceptance.sha256'
$expected=((Get-Content $ShaFile -Raw).Trim().Split(' ')[0]).ToUpperInvariant()
$actual=(Get-FileHash $Apk -Algorithm SHA256).Hash
if($expected -ne $actual){throw 'APK SHA256 mismatch'}
$env:GIT_TERMINAL_PROMPT='0'
$inputText=[string]::Join([Environment]::NewLine,@('protocol=https','host=github.com','',''))
$lines=$inputText | git credential fill 2>$null
$token=($lines | Where-Object{$_ -like 'password=*'} | Select-Object -First 1) -replace '^password=',''
if([string]::IsNullOrWhiteSpace($token)){throw 'Windows GitHub credential unavailable'}
try {
 $headers=@{
  Authorization='Bearer '+$token
  Accept='application/vnd.github+json'
  'X-GitHub-Api-Version'='2022-11-28'
  'User-Agent'='Asterion-Persistently-Signed-Acceptance'
 }
 $api='https://api.github.com/repos/'+$repo
 $existing=$null
 try{$existing=Invoke-RestMethod ($api+'/releases/tags/'+$tag) -Headers $headers -TimeoutSec 25}
 catch{if([int]$_.Exception.Response.StatusCode -ne 404){throw}}
 if($existing){throw 'Release already exists; refusing to replace signed asset'}
 $note='First install of persistently Windows-signed com.azrael.pixivdumpsync.acceptance. Prior app data is preserved in the older package. Verified build, tests, signer and SHA256. Samsung live downloads and all providers are not yet fully accepted. No access controls bypassed.'
 $body=@{tag_name=$tag;target_commitish=$Commit;name='Asterion Downloader v1.3.51 Acceptance';draft=$false;prerelease=$true;body=$note} | ConvertTo-Json -Compress -Depth 5
 $utf8=[System.Text.Encoding]::UTF8.GetBytes($body)
 $release=Invoke-RestMethod ($api+'/releases') -Method Post -Headers $headers -ContentType 'application/json; charset=utf-8' -Body $utf8 -TimeoutSec 40
 $upload=$release.upload_url -replace '\{\?.*$',''
 foreach($asset in @(@{name=$name;path=$Apk;mime='application/vnd.android.package-archive'},@{name=$shaName;path=$ShaFile;mime='text/plain'})){
  $url=$upload+'?name='+[uri]::EscapeDataString($asset.name)
  $done=Invoke-RestMethod -Uri $url -Method Post -Headers $headers -ContentType $asset.mime -InFile $asset.path -TimeoutSec 180
  if([long]$done.size -ne (Get-Item $asset.path).Length){throw 'Uploaded asset size mismatch'}
  Write-Output ('ASSET '+$done.name+' SIZE '+$done.size)
 }
 Write-Output ('RELEASE '+$release.html_url)
 Write-Output ('APK '+('https://github.com/'+$repo+'/releases/download/'+$tag+'/'+$name))
 Write-Output ('SHA256 '+$actual)
 Write-Output ('COMMIT '+$Commit)
}finally{$token=$null;$lines=$null;Remove-Variable token,lines -ErrorAction SilentlyContinue}
