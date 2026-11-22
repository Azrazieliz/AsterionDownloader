#!/usr/bin/env python3
"""PC proof for one REAL publisher-published PornAVHD recording:
watch article -> sole playerrecord iframe -> its ordinary Chrome media
request -> entire CDN MP4 (not URL guessed or extracted from ads).
Full HTTP length, SHA-256, FFprobe duration + actual video required. A 3s
sample, range-only proof, block, unknown size or partial is NOT certified.
No signed CDN URL in logs; temporary whole media is deleted on exit.
"""
import hashlib,ipaddress,json,re,shutil,subprocess,sys,tempfile,time,urllib.parse,urllib.request
from pathlib import Path
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
from pc_pornavhd_chrome_player_20261122 import UA,public_page,EMBED
MAX_BYTES=4_000_000_000
DEADLINE=640
def receipt(stage,**kwargs):
 print(json.dumps({"provider":"pornavhd","stage":stage,
    "full_original_certified":stage=="COMPLETE_SELECTED_PC_FILE",**kwargs},
    sort_keys=True),flush=True)
def safe(raw):
 p=urllib.parse.urlsplit(raw)
 host=(p.hostname or "").lower()
 if(p.scheme!="https" or not host.endswith(".bkcdn.net") or
    p.username or p.password or p.port not in (None,443) or p.fragment or
    not re.fullmatch(r"/[A-Za-z0-9_.%/-]{5,1500}\.mp4",p.path,re.I) or
    "preview" in p.path.lower() or "trailer" in p.path.lower()):return False
 try:
  if not ipaddress.ip_address(host).is_global:return False
 except ValueError:pass
 return True
def collect_selected_media():
 selected=public_page()
 opts=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage",
              "--disable-gpu","--window-size=412,915","--user-agent="+UA):
  opts.add_argument(flag)
 opts.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 driver=webdriver.Chrome(options=opts)
 try:
  driver.set_page_load_timeout(32);driver.get(selected)
  frames=driver.find_elements(By.CSS_SELECTOR,"main iframe[src],article iframe[src]")
  exact=[]
  for item in frames:
   uri=urllib.parse.urlsplit(item.get_attribute("src") or "")
   if(uri.scheme=="https" and uri.hostname=="video.playerrecord.cfd" and
      EMBED.fullmatch(uri.path) and not uri.query and not uri.fragment):
    exact.append(item)
  if len(exact)!=1:raise RuntimeError("Publisher has no unique selected first-party player")
  frame=exact[0].get_attribute("src")
  driver.switch_to.frame(exact[0])
  found={}
  for tick in range(28):
   driver.execute_script("""(function(){
     const root=document.querySelector('#player,.video-player,.player-container');
     const play=root?.querySelector('button[aria-label="Play"],'+
       '.vjs-big-play-button,.plyr__control[data-plyr="play"],.fp-play');
     if(play&&!play.dataset.asterionPcClicked){
        play.dataset.asterionPcClicked='true';play.click();}
   })()""")
   for item in driver.get_log("performance"):
    try:
     data=json.loads(item["message"])["message"]
     if data.get("method")!="Network.responseReceived":continue
     params=data.get("params",{});resp=params.get("response",{})
     media=resp.get("url","")
     if not safe(media):continue
     kind=params.get("type")
     if(kind not in ("Media","Fetch") and
          not str(resp.get("mimeType","")).startswith("video/")):continue
     if int(resp.get("status") or 0) not in (200,206):continue
     found[media]={"host":urllib.parse.urlsplit(media).hostname,
                   "kind":kind,"http":resp.get("status"),
                   "mime":resp.get("mimeType")}
    except Exception:continue
   if len(found)==1 and tick>=5:break
   time.sleep(1)
  receipt("EXACT_CHROME_SELECTED_MEDIA",count=len(found),
      selected_player_host="video.playerrecord.cfd",
      cdn_hosts=list({v["host"] for v in found.values()}))
  if not found:raise RuntimeError("Selected player has no trustworthy CDN MP4")
  # A selected HTML5 player may prefetch a short intro and a full file.
  # Preserve all exact attested requests and distinguish only by VERIFIED
  # original HTTP range size; do not guess which URL is full.
  return list(found),frame
 finally:driver.quit()
def request(media,referer,range_value=None):
 h={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity",
    "Accept":"video/mp4,application/octet-stream;q=0.9"}
 if range_value:h["Range"]=range_value
 return urllib.request.Request(media,headers=h)
def verify():
 started=time.monotonic()
 candidates,referer=collect_selected_media()
 observed=[]
 for index,link in enumerate(candidates):
  try:
   with urllib.request.urlopen(request(link,referer,"bytes=0-255"),timeout=28) as res:
    if(res.status!=206 or not safe(res.url) or
       res.headers.get("Content-Type","").split(";")[0].lower()
            not in ("video/mp4","application/octet-stream")):
     continue
    match=re.fullmatch(r"bytes 0-255/([0-9]{5,12})",
                       res.headers.get("Content-Range",""))
    if not match or res.read(256)[4:8]!=b"ftyp":continue
    size=int(match.group(1))
    receipt("SELECTED_PLAYER_CDN_CANDIDATE",index=index,
        declared_bytes=size,host=urllib.parse.urlsplit(link).hostname,
        verified_mp4_header=True)
    observed.append((link,size))
  except Exception as error:
   receipt("SELECTED_PLAYER_CDN_CANDIDATE_ERROR",index=index,
      error_type=type(error).__name__)
 # A large official MP4 is only a *candidate*, never itself proof of full
 # runtime. Reject two competing large files, and still FFprobe full bytes.
 viable=[item for item in observed if item[1]>=10_000_000 and item[1]<=MAX_BYTES]
 if len(viable)>1:raise RuntimeError("More than one plausible full original")
 if len(viable)==1:link,expected=viable[0]
 elif len(observed)==1:link,expected=observed[0]
 else:raise RuntimeError("Selected iframe exposes no unique full MP4")
 if expected<200_000 or expected>MAX_BYTES:
  raise RuntimeError("Selected media outside bounded PC test size")
 receipt("PC_SELECTED_PUBLISHER_DECLARED_SIZE",declared_bytes=expected,
      cdn_host=urllib.parse.urlsplit(link).hostname,range_http=206)
 if not shutil.which("ffprobe"):raise RuntimeError("ffprobe not installed")
 if shutil.disk_usage(tempfile.gettempdir()).free<expected+500_000_000:
  raise RuntimeError("Insufficient PC scratch for complete selected original")
 receipt("SELECTED_CDN_REAL_BYTES_READY",expected_bytes=expected,
     first_http=206,magic="ftyp",temporary_only=True)
 with tempfile.TemporaryDirectory(prefix="asterion_pornavhd_exact_full_") as directory:
  file=Path(directory)/"selected-original.mp4"
  sha=hashlib.sha256();written=0
  with urllib.request.urlopen(request(link,referer),timeout=28) as response:
   if(response.status not in (200,206) or not safe(response.url)):
    raise RuntimeError("Selected full CDN HTTP or redirect invalid")
   if response.headers.get("Content-Type","").split(";")[0].lower() not in (
       "video/mp4","application/octet-stream"):
    raise RuntimeError("Full selected media response no longer MP4")
   if response.status==206:
    assert response.headers.get("Content-Range")==f"bytes 0-{expected-1}/{expected}", "Selected 206 is partial"
   elif int(response.headers.get("Content-Length") or 0)!=expected:
    raise RuntimeError("Full selected media size changed on resume")
   with file.open("wb") as output:
    while True:
     if time.monotonic()-started>DEADLINE:
      raise RuntimeError("Selected full file deadline elapsed")
     data=response.read(262144)
     if not data:break
     written+=len(data)
     if written>expected:raise RuntimeError("Selected file exceeded original length")
     sha.update(data);output.write(data)
     if written//(16*1024*1024)!=(written-len(data))//(16*1024*1024):
      receipt("SELECTED_PC_TRANSFER_BYTES",received_bytes=written,
          declared_bytes=expected)
  if written!=expected or file.stat().st_size!=expected:
   raise RuntimeError("Selected original file incomplete")
  info=subprocess.run(["ffprobe","-v","error","-of","json",
     "-show_entries","format=duration:stream=codec_type",str(file)],
     capture_output=True,text=True,timeout=50)
  if info.returncode:raise RuntimeError("Selected entire original FFprobe failed")
  obj=json.loads(info.stdout)
  duration=float(obj.get("format",{}).get("duration") or 0)
  kinds=[s.get("codec_type") for s in obj.get("streams",[])]
  if duration<90 or "video" not in kinds:
   raise RuntimeError("Selected player only returned short preview or invalid media")
  receipt("COMPLETE_SELECTED_PC_FILE",declared_bytes=expected,
    received_bytes=written,sha256=sha.hexdigest(),duration_seconds=round(duration,3),
    tracks=kinds,cdn_host=urllib.parse.urlsplit(link).hostname,
    elapsed_seconds=round(time.monotonic()-started,1),
    temporary_media_deleted_after_test=True,android_verified=False)
 return 0
if __name__=="__main__":
 try:sys.exit(verify())
 except Exception as ex:
  receipt("NOT_CERTIFIED",error_type=type(ex).__name__,
      reason=re.sub(r"https?://[^\\s]+","[url]",str(ex))[:155])
  sys.exit(2)
