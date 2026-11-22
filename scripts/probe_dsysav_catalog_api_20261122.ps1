# READ-ONLY: discover the actual public DSYSAV catalog schema via its own
# signed JSON client contract. No video/fragment downloads, cookies or login.
$ErrorActionPreference='Stop'
$ProgressPreference='SilentlyContinue'
$api='https://api.dsav.app/v1'
$salt='AjPuom638LmWfWyeM5YueKuJ9PuWLdRn'
$utf8=[System.Text.Encoding]::UTF8
$key=$utf8.GetBytes('OPQT123412FRANME')
$iv=$utf8.GetBytes('MRDCQP12QPM13412')
function AesCipher([byte[]]$bytes,[bool]$encrypt){
 $aes=[System.Security.Cryptography.Aes]::Create()
 try{
  $aes.Mode=[System.Security.Cryptography.CipherMode]::CBC
  $aes.Padding=[System.Security.Cryptography.PaddingMode]::PKCS7
  $aes.Key=$key;$aes.IV=$iv
  if($encrypt){$transform=$aes.CreateEncryptor()}
  else{$transform=$aes.CreateDecryptor()}
  try{return $transform.TransformFinalBlock($bytes,0,$bytes.Length)}
  finally{$transform.Dispose()}
 }finally{$aes.Dispose()}
}
function ApiRequest([string]$route,[hashtable]$params,$config){
 $request=@{}
 foreach($name in $params.Keys){$request[$name]=$params[$name]}
 $request['route']=$route
 $request['lang']='en'
 $payload=ConvertTo-Json $request -Compress -Depth 8
 $source=$utf8.GetBytes($payload)
 $nonce=[guid]::NewGuid().ToString()
 $timestamp=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
 $digestInput=[Convert]::ToBase64String($source)+$nonce+$timestamp+$salt
 $md5=[System.Security.Cryptography.MD5]::Create()
 try{$sign=([BitConverter]::ToString($md5.ComputeHash(
   $utf8.GetBytes($digestInput)))).Replace('-','').ToLowerInvariant()}
 finally{$md5.Dispose()}
 $encrypted=[Convert]::ToBase64String((AesCipher $source $true))
 $body=ConvertTo-Json @{
  sign=$sign;nonce=$nonce;timestamp=$timestamp;data=$encrypted
 } -Compress
 $headers=@{
  'User-Agent'='Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36'
  'Origin'='https://dsysav.com'
  'Referer'='https://dsysav.com/en'
  'Accept'='application/json'
  'Accept-Language'='en-US,en;q=0.9'
 }
 if($route -ne '/config' -and $config -and
  $config.http_sign_flag -eq $true){
  $keyId=[string]$config.http_sign_id
  $signKey=[string]$config.http_sign_key
  if(!$keyId -or !$signKey){throw 'Publisher API signing config incomplete'}
  $sha=[System.Security.Cryptography.SHA256]::Create()
  try{$d=[Convert]::ToBase64String($sha.ComputeHash($utf8.GetBytes($body)))}
  finally{$sha.Dispose()}
  $contentDigest='sha-256=:'+$d+':'
  $created=[DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
  $signatureParams='("@method" "@target-uri" "content-digest" "@created");created='+$created+';keyid="'+$keyId+'";alg="hmac-sha256"'
  $lines=@('"@method": POST','"@target-uri": '+$route,
    'content-digest: '+$contentDigest,'"@created": '+$created,
    '"@signature-params": '+$signatureParams)
  $text=$lines -join [char]10
  $hmac=[System.Security.Cryptography.HMACSHA256]::new($utf8.GetBytes($signKey))
  try{$signature=[Convert]::ToBase64String($hmac.ComputeHash($utf8.GetBytes($text)))}
  finally{$hmac.Dispose()}
  $headers['Content-Digest']=$contentDigest
  $headers['Signature-Input']='v1='+$signatureParams
  $headers['Signature']='v1=:'+$signature+':'
 }
 $requestArgs=@{Uri=$api;Method='Post';UseBasicParsing=$true
  Headers=$headers;Body=$body;ContentType='application/json'
  TimeoutSec=20;MaximumRedirection=0}
 $response=Invoke-WebRequest @requestArgs
 if([int]$response.StatusCode -ne 200){throw ('API HTTP '+$response.StatusCode)}
 $envelope=$response.Content|ConvertFrom-Json
 if($envelope.code -ne 200 -or !$envelope.data){
  $status=if($envelope.code){[string]$envelope.code}else{'missing_code'}
  $label=if($envelope.msg){[string]$envelope.msg}else{'no_message'}
  throw ('API response route='+$route+' code='+$status+
    ' message='+$label.Substring(0,[Math]::Min(90,$label.Length)))
 }
 $bytes=[Convert]::FromBase64String([string]$envelope.data)
 $plain=$utf8.GetString((AesCipher $bytes $false))
 return ($plain|ConvertFrom-Json)
}
try{
 $config=ApiRequest '/config' @{} $null
 Write-Output ('DSYSAV_CONFIG_SIGNING='+[bool]$config.http_sign_flag)
 # Compare a previously verified SAME-PUBLISHER detail request to the
 # homepage mode. No media bytes or signed URLs are printed or downloaded.
 try{
  $detail=ApiRequest '/videos/search' @{
    search_type='video_detail_filter_id';video_id='MxWT7NmlUoT'
  } $config
  $one=@($detail)
  Write-Output ('DSYSAV_DETAIL_API_WORKS='+($one.Count -gt 0))
 }catch{Write-Output ('DSYSAV_DETAIL_API_ERROR='+($_.Exception.Message -replace 'https?://\S+','[URL]'))}
 # The site's actual getSearchVideoData() uses video_hot_search,
 # offset and limit, NOT the getVideo() 'latest_hot_video' mode.
 $items=ApiRequest '/videos/search' @{
   search_type='video_hot_search';offset=0;limit=20
 } $config
 $rows=@()
 if($items -is [array]){$rows=@($items)}
 elseif($items.PSObject.Properties.Name -contains 'list'){$rows=@($items.list)}
 elseif($items.PSObject.Properties.Name -contains 'data'){$rows=@($items.data)}
 else{$rows=@($items)}
 Write-Output ('DSYSAV_CATALOG_ROWS='+$rows.Count)
 if($rows.Count -gt 0){
  $first=$rows[0]
  Write-Output ('DSYSAV_CATALOG_ROW_KEYS='+((@($first.PSObject.Properties.Name)|Select-Object -First 25) -join ','))
  $valid=@($rows|Where-Object{[string]$_.video_id -match '^[A-Za-z0-9_-]{8,45}$'})
  Write-Output ('DSYSAV_VALID_WATCH_IDS='+$valid.Count)
  Write-Output ('DSYSAV_FIRST_TITLE_LENGTH='+([string]$first.video_title).Length)
  $coverHosts=@($rows|Select-Object -First 8|ForEach-Object{
   try{([uri][string]$_.video_cover).Host}catch{''}
  }|Where-Object{$_}|Select-Object -Unique)
  Write-Output ('DSYSAV_COVER_HOSTS='+($coverHosts -join ','))
 }
 exit 0
}catch{
 Write-Output ('DSYSAV_CATALOG_PROBE_FAILED='+($_.Exception.GetType().Name)+': '+($_.Exception.Message -replace 'https?://\S+','[URL]'))
 exit 1
}
