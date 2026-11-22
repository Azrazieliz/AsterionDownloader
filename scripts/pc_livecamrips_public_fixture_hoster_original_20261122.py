#!/usr/bin/env python3
"""LiveCamRips selected representative public fixture -> attested ABStream
player -> complete original PC. The live publisher is currently HTTP403 and
this test MUST NOT claim live article or owner's exact Android selection pass.
Provenance: Cyberdrop-DL/cyberdrop-dl tests/crawlers/test_cases/livecamrips.py.
"""
import hashlib,json,re,subprocess,sys,tempfile,time,urllib.parse,urllib.request,urllib.error
from pathlib import Path
from bs4 import BeautifulSoup

ARTICLE="https://livecamrips.to/video/14759344"
HOSTER="https://abstream.to/embed/kkrqbqtsdf8q"
PLAYER="https://abstream.to/embed-kkrqbqtsdf8q.html"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
receipt={"provider":"livecamrips","publisher_selected_article":ARTICLE,
  "fixture_selected_player":HOSTER,"live_publisher_article_verified":False,
  "complete_selected_original":False,"source_identity":"Cyberdrop-DL maintained LiveCamRips test fixture"}
def safe(url):
 try:
  u=urllib.parse.urlsplit(url)
  return u.scheme=="https" and bool(u.hostname) and u.username is None and u.password is None and u.port in (None,443)
 except Exception:return False
def get(url, referer,limit=2_000_000):
 request=urllib.request.Request(url,headers={"User-Agent":UA,
   "Referer":referer,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(request,timeout=25) as r:
  if r.status!=200:raise RuntimeError("EXACT_FIXTURE_PLAYER_HTTP_"+str(r.status))
  content=r.read(limit+1)
  if len(content)>limit:raise RuntimeError("SOURCE_CONTENT_UNEXPECTED_SIZE")
  return content,r.headers.get("Content-Type","")
started=time.monotonic()
try:
 data,mime=get(PLAYER,ARTICLE)
 if "html" not in mime.lower():raise RuntimeError("HOSTER_DID_NOT_RETURN_PLAYER_HTML")
 page=data.decode("utf-8","replace")
 if "Video embed restricted for this domain" in page:
  raise RuntimeError("EXACT_HOSTER_REQUIRES_PUBLISHER_BROWSER_REFERER")
 if "File is no longer available" in page:
  raise RuntimeError("EXACT_PUBLISHED_FIXTURE_GONE")
 at=page.find('jwplayer("vplayer").setup')
 if at<0:raise RuntimeError("EXACT_HOSTER_JWPLAYER_NOT_PUBLISHED")
 tail=page[at:at+60_000]
 pos=tail.find("sources:")
 if pos<0:raise RuntimeError("JWPLAYER_SELECTED_SOURCES_MISSING")
 candidate_text=tail[pos:pos+6000]
 found=re.findall(r"""file\s*:\s*['"]([^'"]+)['"]""",candidate_text)
 links=list(dict.fromkeys(x.replace("\\/","/") for x in found if safe(x)))
 if len(links)!=1:raise RuntimeError("NO_SINGLE_SELECTED_ABSTREAM_HLS")
 link=links[0]
 if not urllib.parse.urlsplit(link).path.lower().endswith(".m3u8"):
  raise RuntimeError("HOSTER_SELECTED_MEDIA_IS_NOT_HLS")
 receipt["observed_selected_hls_host"]=urllib.parse.urlsplit(link).hostname
 playlist,manifest_mime=get(link,PLAYER,limit=1_000_000)
 if not playlist.startswith(b"#EXTM3U"):
  raise RuntimeError("HOSTER_SELECTED_HLS_MANIFEST_INVALID")
 with tempfile.TemporaryDirectory(prefix="asterion-livecamrips-fixture-") as td:
  folder=Path(td)
  cmd=[sys.executable,"-m","yt_dlp",
   "--no-playlist","--no-part","--no-mtime","--no-warnings",
   "--retries","2","--fragment-retries","2","--abort-on-unavailable-fragments",
   "--socket-timeout","20","--concurrent-fragments","4",
   "--add-header","Referer:"+PLAYER,"--format","best",
   "-o",str(folder/"original.%(ext)s"),link]
  try:p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=520)
  except subprocess.TimeoutExpired:raise RuntimeError("FULL_PUBLISHED_HOSTER_HLS_TIMEOUT")
  if p.returncode:
   tail=re.sub(r"https?://\S+","[REDACTED]",p.stdout[-400:])
   raise RuntimeError("PUBLISHED_HOSTER_HLS_TRANSFER_FAILED:"+tail)
  files=[x for x in folder.iterdir() if x.is_file() and x.stat().st_size>=10_000_000 and not x.name.endswith((".part",".ytdl"))]
  if len(files)!=1:raise RuntimeError("NO_COMPLETE_PUBLISHED_FIXTURE_VIDEO")
  file=files[0]
  meta=json.loads(subprocess.check_output(["ffprobe","-v","error",
   "-show_entries","format=duration,size:stream=codec_type","-of","json",str(file)],timeout=25,text=True))
  seconds=float(meta.get("format",{}).get("duration") or 0)
  tracks=[x.get("codec_type") for x in meta.get("streams",[])]
  if seconds<90 or "video" not in tracks or "audio" not in tracks:
   raise RuntimeError("PUBLISHED_FIXTURE_ORIGINAL_PREVIEW_OR_MISSING_TRACKS")
  checksum=hashlib.sha256()
  with file.open("rb") as f:
   for chunk in iter(lambda:f.read(1_048_576),b""):checksum.update(chunk)
  receipt.update({"complete_selected_original":True,
    "bytes":file.stat().st_size,"duration_seconds":round(seconds,3),
    "tracks":tracks,"sha256":checksum.hexdigest()})
except Exception as e:
 receipt["reason"]=re.sub(r"https?://\S+","[REDACTED]",str(e))[:340]
finally:
 receipt["elapsed_seconds"]=round(time.monotonic()-started,1)
 Path("pc-livecamrips-fixture-receipt.json").write_text(json.dumps(receipt,indent=2,sort_keys=True))
 print(json.dumps(receipt,sort_keys=True),flush=True)
if not receipt["complete_selected_original"]:sys.exit(2)
