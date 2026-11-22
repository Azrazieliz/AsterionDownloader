#!/usr/bin/env python3
"""Full-file PC proof: actual selected dsysav.com/en watch -> public first-party
JSON -> publisher DASH MPD -> normal player-signed fMP4 segments -> complete
playable, SHA256-verified local video+audio. Never accept trailers/partial files.
No user credentials, CAPTCHA bypass, forged videos or unrelated recommenders.
"""
import base64,concurrent.futures,hashlib,json,re,subprocess,sys,tempfile,time
import urllib.parse,urllib.request,urllib.error,xml.etree.ElementTree as ET
from pathlib import Path
from Crypto.Cipher import AES
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By

CATALOG="https://dsysav.com/en"
SELECTED="https://dsysav.com/en/watch?v=MxWT7NmlUoT"
API_HOST="api.dsav.app"
MEDIA_HOST="oss.dsav.app"
SIGN_SALT="AjPuom638LmWfWyeM5YueKuJ9PuWLdRn"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
result={"provider":"dsysav.com","selected_article":SELECTED,"full_file_verified":False}

def selected_id():
 return urllib.parse.parse_qs(urllib.parse.urlsplit(SELECTED).query)["v"][0]

def public_url(url):
 try:
  u=urllib.parse.urlsplit(url)
  return u.scheme=="https" and u.hostname==MEDIA_HOST and not u.username and not u.password and u.port in (None,443) and not u.fragment
 except Exception:return False

def decrypt_api(raw):
 obj=json.loads(raw)
 if obj.get("code")!=200 or not isinstance(obj.get("data"),str):
  return None
 payload=base64.b64decode(obj["data"],validate=True)
 if len(payload)%16:raise ValueError("Publisher AES response is unaligned")
 data=AES.new(b"OPQT123412FRANME",AES.MODE_CBC,b"MRDCQP12QPM13412").decrypt(payload)
 pad=data[-1]
 if not 1<=pad<=16 or data[-pad:]!=bytes([pad])*pad:
  raise ValueError("Publisher response has invalid padding")
 return json.loads(data[:-pad].decode("utf-8"))

def browser_selected():
 options=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage",
              "--disable-gpu","--window-size=1400,900"):
  options.add_argument(flag)
 options.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 d=webdriver.Chrome(options=options)
 config={}
 selected=None
 seen=set()
 def poll():
  nonlocal config,selected
  request_ids=[]
  for row in d.get_log("performance"):
   msg=json.loads(row["message"]).get("message",{})
   if msg.get("method")!="Network.responseReceived":continue
   p=msg.get("params",{})
   res=p.get("response",{})
   url=res.get("url","")
   if urllib.parse.urlsplit(url).hostname!=API_HOST or int(res.get("status") or 0)!=200:
    continue
   if res.get("mimeType","").split(";")[0]!="application/json":continue
   rid=p.get("requestId")
   if rid and rid not in seen:request_ids.append(rid)
  for rid in request_ids:
   try:
    body=d.execute_cdp_cmd("Network.getResponseBody",{"requestId":rid})
    raw=body["body"]
    if body.get("base64Encoded"):raw=base64.b64decode(raw).decode("utf-8")
    decoded=decrypt_api(raw)
    if isinstance(decoded,dict) and "video_sign_expire" in decoded:
     config=decoded
    records=decoded if isinstance(decoded,list) else [decoded]
    good=[x for x in records if isinstance(x,dict) and x.get("video_id")==selected_id()
        and public_url(x.get("video_url","")) and
        urllib.parse.urlsplit(x["video_url"]).path.endswith(".mpd")]
    if len(good)==1:selected=good[0]
    seen.add(rid)
   except Exception:pass
 try:
  d.set_page_load_timeout(22)
  d.execute_cdp_cmd("Network.enable",{})
  try:d.get(CATALOG)
  except Exception:pass
  found=False
  for tick in range(32):
   poll()
   links=d.find_elements(By.CSS_SELECTOR,"a[href]")
   targets=[x for x in links if (x.get_attribute("href") or "")==SELECTED]
   if len(targets)==1:
    found=True
    try:d.execute_script("arguments[0].scrollIntoView({block:'center'});",targets[0])
    except Exception:pass
    try:targets[0].click()
    except Exception:
     try:d.execute_script("arguments[0].click()",targets[0])
     except Exception:pass
    break
   time.sleep(.55)
  if not found:raise RuntimeError("EXACT_SELECTED_WATCH_NOT_PRESENT_IN_REAL_CATALOG")
  for tick in range(38):
   poll()
   if selected is not None:break
   time.sleep(.55)
  if selected is None:raise RuntimeError("EXACT_WATCH_NOT_PUBLISHED_BY_FIRST_PARTY_API")
  if urllib.parse.urlsplit(d.current_url).path!="/en/watch":
   raise RuntimeError("PUBLISHER_BROWSER_LEFT_SELECTED_WATCH")
  return selected,config
 finally:d.quit()

def duration_seconds(raw):
 if isinstance(raw,(int,float)):return float(raw)
 text=str(raw).strip()
 if ":" in text:
  values=[float(t) for t in text.split(":")]
  out=0.
  for number in values:out=60*out+number
  return out
 try:return float(text)
 except Exception:return 0.

def http_get(url,referer=SELECTED,maximum=2400000):
 req=urllib.request.Request(url,headers={"User-Agent":UA,
    "Referer":referer,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=26) as response:
  if response.status not in (200,206):raise RuntimeError("PUBLISHER_MEDIA_HTTP_"+str(response.status))
  content=response.read(maximum+1)
  if len(content)>maximum:raise RuntimeError("PUBLISHER_RESPONSE_TOO_LARGE")
  return content

def xmlchildren(parent,name):
 return [x for x in list(parent) if x.tag.rsplit("}",1)[-1]==name]

def firstchild(parent,name):
 return next(iter(xmlchildren(parent,name)),None)

def sign_url(path,expiry):
 u=urllib.parse.urlsplit(path)
 if not public_url(path):raise RuntimeError("SELECTED_SEGMENT_LEFT_PUBLISHER_MEDIA_HOST")
 if not re.fullmatch(r"/mpd/[A-Za-z0-9_./%-]{10,300}\.(?:mpd|m4s|mp4)",u.path,re.I):
  raise RuntimeError("UNEXPECTED_PUBLISHER_SEGMENT_PATH")
 import base64
 digest=hashlib.md5((SIGN_SALT+u.path+str(expiry)).encode()).digest()
 signature=base64.urlsafe_b64encode(digest).decode().rstrip("=")
 return url_without_query(path)+"?sign="+signature+"&expire="+str(expiry)

def url_without_query(source):
 u=urllib.parse.urlsplit(source)
 return urllib.parse.urlunsplit((u.scheme,u.netloc,u.path,"",""))

def render_template(pattern,rep,number):
 text=pattern.replace("$RepresentationID$",rep)
 text=re.sub(r"\$Number(?:%0(\d+)d)?\$",lambda m:str(number).zfill(int(m.group(1) or "0")),text)
 if "$" in text:raise RuntimeError("UNKNOWN_PUBLISHER_SEGMENT_TEMPLATE")
 return text

def parse_tracks(mpd,url,expected):
 tree=ET.fromstring(mpd)
 periods=xmlchildren(tree,"Period")
 if len(periods)!=1:raise RuntimeError("NOT_ONE_PUBLISHED_DASH_PERIOD")
 root_base=firstchild(tree,"BaseURL")
 base=url
 if root_base is not None and root_base.text:
  base=urllib.parse.urljoin(url,root_base.text.strip())
 tracks=[]
 for adaptation in xmlchildren(periods[0],"AdaptationSet"):
  rep_nodes=xmlchildren(adaptation,"Representation")
  kind=adaptation.get("contentType","")
  shared=firstchild(adaptation,"SegmentTemplate")
  for rep in rep_nodes:
   content=kind or ("audio" if (rep.get("mimeType") or adaptation.get("mimeType") or "").startswith("audio") else "video")
   if content not in ("audio","video"):continue
   tpl=firstchild(rep,"SegmentTemplate") or shared
   if tpl is None:continue
   media=tpl.get("media","")
   initializer=tpl.get("initialization","")
   if not media or not initializer:continue
   rid=rep.get("id","0")
   rate=int(tpl.get("timescale") or 1000)
   start=int(tpl.get("startNumber") or 1)
   if rate<=0 or start<1:continue
   timeline=firstchild(tpl,"SegmentTimeline")
   segment_durations=[]
   if timeline is not None:
    for point in xmlchildren(timeline,"S"):
     length=int(point.get("d","0"))
     repeat=int(point.get("r","0"))
     if length<=0 or repeat<0 or repeat>6000:raise RuntimeError("INVALID_PUBLISHER_SEGMENT_TIMELINE")
     segment_durations.extend([length/rate]*(repeat+1))
     if len(segment_durations)>6000:raise RuntimeError("TOO_MANY_PUBLISHER_SEGMENTS")
   else:
    per=int(tpl.get("duration") or 0)
    if per<=0:continue
    segment_durations=[per/rate]*int(expected/(per/rate)+.999)
   if not 1<=len(segment_durations)<=6000:continue
   rep_base=firstchild(rep,"BaseURL")
   rep_location=urllib.parse.urljoin(base,rep_base.text.strip()) if rep_base is not None and rep_base.text else base
   init=urllib.parse.urljoin(rep_location,render_template(initializer,rid,start))
   segments=[urllib.parse.urljoin(rep_location,render_template(media,rid,start+i))
      for i in range(len(segment_durations))]
   if not all(public_url(x) for x in [init]+segments):continue
   total=sum(segment_durations)
   if total<.9*expected or total>1.1*expected:continue
   tracks.append({"kind":content,"bandwidth":int(rep.get("bandwidth") or 0),
       "height":int(rep.get("height") or 0),"init":init,"segments":segments,
       "duration":round(total,3)})
 if not tracks:raise RuntimeError("PUBLISHER_MPD_HAS_NO_COMPLETE_RUNTIME_TRACKS")
 videos=sorted((t for t in tracks if t["kind"]=="video"),
      key=lambda t:(t["height"]>540,t["height"]<300,t["height"],t["bandwidth"]))
 audios=sorted((t for t in tracks if t["kind"]=="audio"),key=lambda t:t["bandwidth"])
 if not videos or not audios:raise RuntimeError("PUBLISHER_MPD_MISSING_AUDIO_OR_VIDEO")
 return videos[0],audios[0]

def get_segment(url,target,deadline):
 if time.monotonic()>deadline:raise RuntimeError("TIME_LIMIT_REACHED_BEFORE_SEGMENT")
 for n in range(2):
  try:
   data=http_get(url,maximum=8_000_000)
   if len(data)<100:raise RuntimeError("EMPTY_OR_PREVIEW_MEDIA_FRAGMENT")
   target.write_bytes(data)
   return len(data)
  except urllib.error.HTTPError as error:
   if error.code in (401,402,403):raise RuntimeError("PUBLISHER_SIGNED_SEGMENT_HTTP_"+str(error.code)) from error
   if n:raise
  except Exception:
   if n:raise
 raise RuntimeError("PUBLISHER_SEGMENT_UNAVAILABLE")

def download_track(track,expiry,folder,deadline):
 urls=[track["init"]]+track["segments"]
 signed=[sign_url(u,expiry) for u in urls]
 checks=[0,len(signed)//2,len(signed)-1]
 for pos in sorted(set(checks)):
  with tempfile.TemporaryDirectory(prefix="dsysav_probe_") as temp:
   get_segment(signed[pos],Path(temp)/"part.m4s",deadline)
 pieces=folder/(track["kind"]+"_parts");pieces.mkdir()
 files=[pieces/(str(i).zfill(6)+".m4s") for i in range(len(signed))]
 with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
  futures=[pool.submit(get_segment,u,path,deadline) for u,path in zip(signed,files)]
  lengths=[f.result() for f in futures]
 if any(n<100 for n in lengths):raise RuntimeError("INCOMPLETE_SIGNED_SEGMENTS")
 combined=folder/(track["kind"]+"_fragments.mp4")
 with combined.open("wb") as out:
  for file in files:
   with file.open("rb") as inp:
    for block in iter(lambda:inp.read(1048576),b""):out.write(block)
   file.unlink()
 pieces.rmdir()
 if combined.stat().st_size!=sum(lengths):raise RuntimeError("SEGMENT_BYTE_SUM_MISMATCH")
 return combined,len(urls),combined.stat().st_size

def run():
 start=time.monotonic()
 video,config=browser_selected()
 result["selected_video_id"]=video["video_id"]
 source=video["video_url"]
 if not public_url(source) or not source.split("?")[0].endswith(".mpd"):
  raise RuntimeError("SELECTED_API_DID_NOT_PUBLISH_FIRST_PARTY_MPD")
 runtime=duration_seconds(video.get("video_duration"))
 if runtime<600 or runtime>8*3600:raise RuntimeError("INVALID_SELECTED_PUBLISHED_RUNTIME")
 result["publisher_runtime_seconds"]=runtime
 result["selected_source_host"]=urllib.parse.urlsplit(source).hostname
 response=http_get(source,maximum=1_600_000).decode("utf-8","replace")
 if "<MPD" not in response:raise RuntimeError("PUBLISHER_SOURCE_IS_NOT_DASH_MPD")
 v,a=parse_tracks(response,source,runtime)
 result["publisher_mpd_tracks"]={"video":{"height":v["height"],"segments":len(v["segments"])},
     "audio":{"segments":len(a["segments"])}}
 configured=config.get("video_sign_expire")
 expiry=int(configured) if isinstance(configured,(int,str)) and str(configured).isdigit() else 0
 if expiry<int(time.time())+1200:expiry=int(time.time())+3600
 result["public_client_expiry_config_available"]=bool(configured)
 deadline=time.monotonic()+740
 with tempfile.TemporaryDirectory(prefix="asterion-dsysav-full-") as temp:
  root=Path(temp)
  video_file,vcount,vbytes=download_track(v,expiry,root,deadline)
  audio_file,acount,abytes=download_track(a,expiry,root,deadline)
  final=root/"selected-original-complete.mp4"
  ff=["ffmpeg","-hide_banner","-loglevel","error","-y",
      "-i",str(video_file),"-i",str(audio_file),"-map","0:v:0",
      "-map","1:a:0","-c","copy","-movflags","+faststart",str(final)]
  p=subprocess.run(ff,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=100)
  if p.returncode:raise RuntimeError("PUBLISHER_FRAGMENTS_CANNOT_REMUX_PLAYABLY")
  meta=json.loads(subprocess.check_output(["ffprobe","-v","error",
      "-show_entries","format=duration,size:stream=codec_type","-of","json",str(final)],
      timeout=30,text=True))
  seconds=float(meta.get("format",{}).get("duration") or 0)
  tracks=[x.get("codec_type") for x in meta.get("streams",[])]
  if seconds<600 or abs(seconds-runtime)>max(15,runtime*.08) or "audio" not in tracks or "video" not in tracks:
   raise RuntimeError("PUBLISHER_ORIGINAL_RUNTIME_OR_TRACKS_MISMATCH")
  if final.stat().st_size<30_000_000:raise RuntimeError("ORIGINAL_TOO_SMALL")
  h=hashlib.sha256()
  with final.open("rb") as f:
   for block in iter(lambda:f.read(1048576),b""):h.update(block)
  result.update({"full_file_verified":True,"bytes":final.stat().st_size,
     "duration_seconds":round(seconds,3),"sha256":h.hexdigest(),"tracks":tracks,
     "fragments_completed":{"video":vcount,"audio":acount},
     "verified_fragment_bytes":vbytes+abytes,"elapsed_seconds":round(time.monotonic()-start,1)})

try:run()
except Exception as exc:
 result["reason"]=re.sub(r"https?://\S+","[REDACTED]",str(exc))[:360]
finally:
 Path("pc-dsysav-full-dash-receipt.json").write_text(json.dumps(result,sort_keys=True,indent=2))
 print(json.dumps(result,sort_keys=True),flush=True)
if not result["full_file_verified"]:sys.exit(2)
