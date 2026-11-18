#!/usr/bin/env python3
"""Exact selected UncensoredHentai complete original via ordinary NHPlayer AJAX.

Publisher watch -> unique NHPlayer /v/ -> published first-party /player.php ->
normal isolated Edge first-party AJAX get-video-url-v2.php (HTTP 200) ->
window.videoReady -> signed r2.1hanime.com selected MP4. No challenge
bypass, fabricated tokens, unrelated player, fallback ads, or public URL logs.
Four bounded HTTPS Range lanes verify Content-Range, total byte ledger, media
audio+video FFprobe, SHA256. Media lives only in a guarded D: temp folder.
"""
import concurrent.futures,hashlib,json,re,shutil,subprocess,sys,tempfile,time,urllib.parse,urllib.request
from pathlib import Path
from inspect_uncensored_nhplayer_selected_cdp_network import main as real_browser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
STAGE=Path(r"D:\AsterionControlRelease\diagnostics")
def request(media,player,interval,timeout=32):
 return urllib.request.Request(media,headers={
  "User-Agent":UA,"Referer":player,
  "Accept-Encoding":"identity","Range":f"bytes={interval[0]}-{interval[1]}"})
def selected_complete(media,player,frame):
 p=urllib.parse.urlsplit(media)
 if(p.scheme!="https" or p.hostname!="r2.1hanime.com" or p.port or
    p.username or p.password or p.fragment or not p.path.lower().endswith(".mp4") or
    not set(urllib.parse.parse_qs(p.query))=={"verify"} or
    re.search(r"(?:preview|teaser|sample|trailer)",p.path,re.I)):
  raise RuntimeError("Selected first-party AJAX did not authorize exact media CDN")
 with urllib.request.urlopen(request(media,player,(0,0)),timeout=25) as response:
  head=response.read(1)
  mime=response.headers.get("Content-Type","").lower().split(";")[0]
  spec=response.headers.get("Content-Range","")
  rx=re.fullmatch(r"bytes 0-0/([0-9]+)",spec)
  if response.status!=206 or mime!="video/mp4" or head==b"" or not rx:
   raise RuntimeError("Selected exact file lacks verifiable byte-range original")
  total=int(rx.group(1))
  etag=response.headers.get("ETag")
 if total<5_000_000 or total>6_000_000_000:
  raise RuntimeError("Selected publisher file size invalid")
 if not STAGE.is_dir() or shutil.disk_usage(STAGE).free < total+1_000_000_000:
  raise RuntimeError("Insufficient isolated D drive staging capacity")
 print(json.dumps({"provider":"uncensoredhentai","stage":"SELECTED_CDN_TOTAL_LENGTH",
   "publisher_ajax_host":"nhplayer.com","selected_media_host":p.hostname,
   "content_type":mime,"expected_bytes":total,"range_verified":True,
   "etag_present":bool(etag),"complete_original_verified":False}),flush=True)
 start=time.monotonic();lanes=4
 with tempfile.TemporaryDirectory(prefix="asterion_uncensored_selected_full_",dir=STAGE) as folder:
  folder=Path(folder)
  chunks=[]
  for lane in range(lanes):
   first=(total*lane)//lanes
   last=(total*(lane+1))//lanes-1
   chunks.append((lane,first,last))
  def receive(lane,first,last):
   expected=last-first+1
   out=folder/f"{lane:02d}.part"
   for attempt in range(3):
    try:
     with urllib.request.urlopen(request(media,player,(first,last)),timeout=45) as resp:
      if resp.status!=206 or resp.headers.get("Content-Range","")!=f"bytes {first}-{last}/{total}":
       raise RuntimeError("Selected media host changed exact Range/total")
      if resp.headers.get("Content-Type","").split(";")[0].lower()!="video/mp4":
       raise RuntimeError("Selected CDN did not deliver video/mp4")
      if etag and resp.headers.get("ETag") and resp.headers.get("ETag")!=etag:
       raise RuntimeError("Selected original changed ETag")
      copied=0
      with out.open("wb") as fd:
       while copied<expected:
        data=resp.read(min(1_048_576,expected-copied))
        if not data:break
        fd.write(data);copied+=len(data)
     if copied==expected and out.stat().st_size==expected:
      return {"lane":lane,"bytes":copied}
     raise RuntimeError("Selected CDN truncated exact source lane")
    except Exception:
     if attempt==2:raise
     time.sleep(0.8+attempt)
   raise RuntimeError("Selected lane retries exhausted")
  with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
   futures={pool.submit(receive,*chunk):chunk[0] for chunk in chunks}
   verified=[]
   for future in concurrent.futures.as_completed(futures):
    receipt=future.result()
    verified.append(receipt)
    print(json.dumps({"provider":"uncensoredhentai",
      "stage":"SELECTED_CDN_BYTE_LANE",
      "lane":receipt["lane"],"lane_bytes":receipt["bytes"],
      "finished_lanes":len(verified),"total_lanes":lanes,
      "complete_original_verified":False}),flush=True)
  if sum(x["bytes"] for x in verified)!=total:
   raise RuntimeError("Byte ledger differs from publisher Content-Range total")
  result=folder/"publisher-selected-original.mp4"
  with result.open("wb") as out:
   for lane,first,last in chunks:
    with (folder/f"{lane:02d}.part").open("rb") as stream:
     shutil.copyfileobj(stream,out,length=1_048_576)
  if result.stat().st_size!=total:
   raise RuntimeError("Final selected file lacks exact original byte count")
  probe=shutil.which("ffprobe")
  if not probe:raise RuntimeError("FFprobe missing")
  ptest=subprocess.run([probe,"-v","error","-show_entries",
       "format=duration,format_name:stream=codec_type,duration,width,height",
       "-of","json",str(result)],capture_output=True,text=True,timeout=50)
  if ptest.returncode!=0:raise RuntimeError("Full selected original did not pass FFprobe")
  obj=json.loads(ptest.stdout)
  duration=float(obj.get("format",{}).get("duration") or 0)
  tracks=[x.get("codec_type") for x in obj.get("streams",[])]
  if duration<120 or "video" not in tracks or "audio" not in tracks:
   raise RuntimeError("Selected original is not a complete playable audio/video")
  digest=hashlib.sha256()
  with result.open("rb") as fd:
   while True:
    data=fd.read(1_048_576)
    if not data:break
    digest.update(data)
  print(json.dumps({"provider":"uncensoredhentai",
   "status":"COMPLETE_SELECTED_PC_FILE",
   "bytes":total,"duration_seconds":round(duration,3),
   "sha256_prefix":digest.hexdigest()[:24],
   "video":True,"audio":True,
   "selected_owner_nhplayer_ajax":True,
   "elapsed_seconds":round(time.monotonic()-start,2),
   "android_receipt_verified":False,
   "temp_media_retained":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(real_browser(verified_source_callback=selected_complete))
 except Exception as e:
  print(json.dumps({"provider":"uncensoredhentai",
   "status":"NOT_COMPLETE",
   "error_type":type(e).__name__,
   "message":re.sub(r"https?://\S+","[redacted]",str(e))[:120],
   "android_receipt_verified":False}),flush=True)
  sys.exit(3)
