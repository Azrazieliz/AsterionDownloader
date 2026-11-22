#!/usr/bin/env python3
"""Complete PC transfer gate for CURRENT, genuinely selected LiveCamRips movie.
Provenance from owner Windows headed Microsoft Edge, Asterion Control
adl-livecam-pc-vidara-20261122-15: the live public catalog selected article
/video/16892369; its unique non-ad #iframe-video is vidara.to/a806a19ec33a.
The old public fixture /video/14759344 was removed/redirected; never conflate.
Only API-published selected original -> complete audio/video file gets PASS.
"""
from __future__ import annotations
import hashlib,json,re,subprocess,sys,tempfile,time,urllib.parse,urllib.request,urllib.error
from pathlib import Path

SELECTED="https://livecamrips.to/video/16892369"
EMBED="https://vidara.to/a806a19ec33a"
FILECODE="a806a19ec33a"
API="https://vidara.to/api/stream"
UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
result={"provider":"livecamrips","publisher_selected_article":SELECTED,
   "selected_embed_host":"vidara.to","selected_embed_filecode":FILECODE,
   "selected_source_provenance":"owner Windows headed Edge live first-party selected /video/16892369 #iframe-video",
   "complete_selected_original":False}
start=time.monotonic()

def is_safe_https(url):
 try:
  u=urllib.parse.urlsplit(url)
  if u.scheme!="https" or not u.hostname or u.username or u.password or u.port not in (None,443):
   return False
  if u.fragment or u.hostname.endswith((".local",".internal")):return False
  if re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}",u.hostname):return False
  if ":" in u.hostname:return False
  return True
 except (ValueError,TypeError):return False

def expected_source():
 if not is_safe_https(EMBED) or urllib.parse.urlsplit(EMBED).hostname!="vidara.to":
  raise RuntimeError("PUBLISHER_EMBED_NOT_VERIFIED")
 assert urllib.parse.urlsplit(EMBED).path=="/"+FILECODE
 body=json.dumps({"device":"web","filecode":FILECODE},separators=(",",":")).encode()
 request=urllib.request.Request(API,data=body,method="POST",headers={
    "User-Agent":UA,"Accept":"application/json",
    "Content-Type":"application/json","Origin":"https://vidara.to",
    "Referer":EMBED})
 try:
  with urllib.request.urlopen(request,timeout=28) as response:
   if response.status!=200:raise RuntimeError("VIDARA_API_HTTP_"+str(response.status))
   if "json" not in (response.headers.get("Content-Type","")).lower():
    raise RuntimeError("VIDARA_API_NON_JSON")
   raw=response.read(350000)
 except urllib.error.HTTPError as e:
  raise RuntimeError("VIDARA_API_HTTP_"+str(e.code)) from e
 data=json.loads(raw)
 if not isinstance(data,dict):
  raise RuntimeError("VIDARA_API_INVALID_RESPONSE")
 link=data.get("streaming_url")
 if not isinstance(link,str) or not is_safe_https(link):
  raise RuntimeError("VIDARA_NO_HTTPS_SELECTED_ORIGINAL")
 if not urllib.parse.urlsplit(link).path.lower().endswith(".m3u8"):
  raise RuntimeError("VIDARA_NOT_PUBLISHED_ORIGINAL_HLS")
 if re.search(r"(?i)preview|trailer|teaser|sample",urllib.parse.urlsplit(link).path):
  raise RuntimeError("VIDARA_RETURNED_PREVIEW")
 result["selected_source_host"]=urllib.parse.urlsplit(link).hostname
 return link

def full_file(link):
 with tempfile.TemporaryDirectory(prefix="asterion-lcr-selected-pc-") as temp:
  out=Path(temp)
  cmd=[sys.executable,"-m","yt_dlp",
    "--no-playlist","--no-part","--no-mtime","--no-warnings",
    "--retries","3","--fragment-retries","6","--abort-on-unavailable-fragments",
    "--socket-timeout","30","--concurrent-fragments","12",
    "--max-filesize","6000M","--format","bestvideo+bestaudio/best",
    "--add-header","Referer:"+EMBED,"--add-header","Origin:https://vidara.to",
    "-o",str(out/"original.%(ext)s"),link]
  try:
   p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                     text=True,timeout=1600)
  except subprocess.TimeoutExpired:
   raise RuntimeError("SELECTED_FULL_TRANSFER_TIMED_OUT")
  if p.returncode:
   detail=re.sub(r"https?://\S+","[REDACTED_SOURCE]",p.stdout[-650:])
   raise RuntimeError("SELECTED_FULL_TRANSFER_FAILED:"+detail)
  files=[x for x in out.iterdir() if x.is_file()
         and x.stat().st_size>10_000_000
         and not x.name.endswith((".part",".ytdl",".tmp"))]
  if len(files)!=1:
   raise RuntimeError("NO_SINGLE_FINISHED_SELECTED_ORIGINAL")
  path=files[0]
  meta=json.loads(subprocess.check_output(
    ["ffprobe","-v","error","-show_entries",
     "format=duration,size:stream=codec_type","-of","json",str(path)],
    timeout=35,text=True))
  duration=float(meta.get("format",{}).get("duration") or 0)
  tracks=[x.get("codec_type") for x in meta.get("streams",[])]
  if duration<120:
   raise RuntimeError("SHORT_PREVIEW_OR_TRUNCATED_ORIGINAL")
  if "video" not in tracks or "audio" not in tracks:
   raise RuntimeError("ORIGINAL_MISSING_VIDEO_OR_AUDIO")
  size=path.stat().st_size
  if int(meta.get("format",{}).get("size") or 0)!=size:
   raise RuntimeError("ORIGINAL_FFPROBE_SIZE_MISMATCH")
  digest=hashlib.sha256()
  with path.open("rb") as handle:
   for block in iter(lambda:handle.read(1048576),b""):
    digest.update(block)
  return {"bytes":size,"duration_seconds":round(duration,3),
          "tracks":tracks,"sha256":digest.hexdigest(),
          "complete_selected_original":True}

try:
 source=expected_source()
 result.update(full_file(source))
except Exception as e:
 result["reason"]=re.sub(r"https?://\S+","[REDACTED_URL]",str(e))[:560]
finally:
 result["elapsed_seconds"]=round(time.monotonic()-start,1)
 Path("pc-livecamrips-current-original-receipt.json").write_text(
  json.dumps(result,sort_keys=True,indent=2))
 print(json.dumps(result,sort_keys=True),flush=True)
if not result["complete_selected_original"]:
 sys.exit(2)
