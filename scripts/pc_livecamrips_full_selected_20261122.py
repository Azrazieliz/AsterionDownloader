#!/usr/bin/env python3
"""LiveCamRips one publisher-selected archived /video/<id> -> FULL PC original.
Public representative URL is from Cyberdrop-DL's LiveCamRips test fixture.
Accept only the live page's own #iframe-video, never use fixtures as media
evidence, never silently bypass 403, never treat hoster HTML as a video.
"""
import hashlib,json,re,subprocess,sys,tempfile,time,urllib.parse,urllib.request,urllib.error
from pathlib import Path
from bs4 import BeautifulSoup
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.common.exceptions import TimeoutException

SELECTED="https://livecamrips.to/video/14759344"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
ALLOWED={"vidara.to","mixdrop.ag","mixdrop.co","mixdrop.to","mdzsmutpcvykb.net","abstream.to"}
record={"provider":"livecamrips","selected_article":SELECTED,"complete_selected_original":False}
def safe(url):
 try:
  u=urllib.parse.urlsplit(url)
  return u.scheme=="https" and bool(u.hostname) and not u.username and not u.password and u.port in (None,443)
 except Exception:return False

def selected_frame(url):
 if not safe(url):return False
 uri=urllib.parse.urlsplit(url)
 path=uri.path
 if uri.hostname=="vidara.to":return bool(re.fullmatch(r"/(?:e/|v/)?[A-Za-z0-9_-]{8,50}/?",path)) and not uri.query
 if uri.hostname=="abstream.to":return bool(re.fullmatch(r"/(?:embed/|embed-)[A-Za-z0-9_-]{7,90}(?:\.html)?/?",path)) and not uri.query
 if uri.hostname in ("mixdrop.ag","mixdrop.co","mixdrop.to","mdzsmutpcvykb.net"):
  return bool(re.fullmatch(r"/e/[A-Za-z0-9_-]{5,64}/?",path)) and not uri.query
 return False

def probe():
 opts=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage",
              "--disable-gpu","--window-size=1366,900"):
  opts.add_argument(flag)
 opts.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 d=webdriver.Chrome(options=opts)
 try:
  d.set_page_load_timeout(24)
  try:d.get(SELECTED)
  except TimeoutException:pass
  for i in range(22):
   title=d.title.lower()
   body=(d.execute_script("return document.body?.innerText||''") or "").lower()
   if any(word in title for word in ("just a moment","access denied","forbidden")):
    raise RuntimeError("PUBLISHER_ACCESS_GATE_HTTP_403_OR_VERIFICATION")
   if "verify you are human" in body or "checking your browser" in body:
    raise RuntimeError("PUBLISHER_REQUIRES_NORMAL_SITE_VERIFICATION")
   data=d.execute_script("""return {title:document.title,
     route:location.pathname,frame:[...document.querySelectorAll('iframe#iframe-video[src]')]
      .filter(x=>!x.closest('aside,.related,.recommended,.ads,.advertisement,[data-ad]'))
      .map(x=>x.src),
     visible:!!document.querySelector('h1'),bodyLength:(document.body?.innerText||'').length};""")
   if data["route"]=="/video/14759344" and len(data["frame"])==1:
    url=data["frame"][0]
    if not selected_frame(url):raise RuntimeError("PUBLISHER_SELECTED_IFRAME_HOST_UNSUPPORTED")
    return url
   time.sleep(.65)
  raise RuntimeError("PUBLISHER_403_OR_NO_EXACT_SELECTED_IFRAME")
 finally:d.quit()

def verified_transfer(source,referer):
 from urllib.parse import urlsplit
 link=source
 if urlsplit(source).hostname=="vidara.to":
  video_id=urlsplit(source).path.rstrip("/").split("/")[-1]
  # Publisher-approved Vidara player API, via observed /e/<filecode>,
  # as implemented in Cyberdrop-DL's normal public Vidara crawler.
  req=urllib.request.Request("https://vidara.to/api/stream",
      headers={"User-Agent":UA,"Referer":source,"Origin":"https://vidara.to",
               "Content-Type":"application/json"},
      data=json.dumps({"device":"web","filecode":video_id}).encode())
  with urllib.request.urlopen(req,timeout=23) as response:
   if response.status!=200:raise RuntimeError("VIDARA_PUBLIC_API_STATUS_"+str(response.status))
   out=json.loads(response.read(400000))
  link=out.get("streaming_url")
  if not safe(link) or not urllib.parse.urlsplit(link).path.lower().endswith(".m3u8"):
   raise RuntimeError("VIDARA_NO_PUBLISHED_SIGNED_HLS")
  record["player_api_verified"]=True
 with tempfile.TemporaryDirectory(prefix="asterion-lcr-selected-") as td:
  root=Path(td)
  cmd=[sys.executable,"-m","yt_dlp","--no-playlist","--no-mtime",
      "--no-part","--no-warnings","--retries","2",
      "--fragment-retries","3","--abort-on-unavailable-fragments",
      "--socket-timeout","25","--concurrent-fragments","4",
      "--add-header","Referer:"+source,"--format","best",
      "-o",str(root/"selected.%(ext)s"),link]
  try:p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=540)
  except subprocess.TimeoutExpired:raise RuntimeError("FULL_TRANSFER_TIMEOUT")
  if p.returncode:
   tail=re.sub(r"https?://\S+","[REDACTED]",p.stdout[-400:])
   raise RuntimeError("SELECTED_HOSTER_TRANSFER_FAILED:"+tail)
  finals=[x for x in root.iterdir() if x.is_file() and x.stat().st_size>15000000
      and not x.name.endswith((".part",".ytdl"))]
  if len(finals)!=1:raise RuntimeError("NO_ONE_FINALIZED_SELECTED_FILE")
  file=finals[0]
  meta=json.loads(subprocess.check_output(["ffprobe","-v","error",
      "-show_entries","format=duration,size:stream=codec_type","-of","json",str(file)],
      text=True,timeout=35))
  seconds=float(meta.get("format",{}).get("duration") or 0)
  tracks=[x.get("codec_type") for x in meta.get("streams",[])]
  if seconds<90 or "video" not in tracks or "audio" not in tracks:
   raise RuntimeError("INVALID_OR_SHORT_RECORDING")
  sha=hashlib.sha256()
  with file.open("rb") as stream:
   for block in iter(lambda:stream.read(1048576),b""):sha.update(block)
  return {"complete_selected_original":True,"bytes":file.stat().st_size,
          "duration_seconds":round(seconds,3),"tracks":tracks,
          "sha256":sha.hexdigest(),"attribution":"live #iframe-video of /video/14759344"}

try:
 frame=probe()
 record["selected_iframe_host"]=urllib.parse.urlsplit(frame).hostname
 record.update(verified_transfer(frame,SELECTED))
except Exception as e:
 record["reason"]=re.sub(r"https?://\S+","[REDACTED]",str(e))[:380]
finally:
 Path("pc-livecamrips-selected-full-receipt.json").write_text(json.dumps(record,sort_keys=True,indent=2))
 print(json.dumps(record,sort_keys=True),flush=True)
if not record["complete_selected_original"]:sys.exit(2)
