#!/usr/bin/env python3
"""DSAV fallback: actual PC selected full playable original or fail.
Only the exact public article's ONE browser-observed HLS and real long video.
Never use an unrelated ad, trailer, fake index or guessed CDN URL."""
import json,re,sys,time,urllib.parse
from pathlib import Path
import pc_full_selected_provider_acceptance_20261122 as core
SELECTED="https://dsav123.wiki/video/2019329099956133888/"
out={"provider":"dsav","selected_page":"public representative","complete_selected_original":False}
started=time.monotonic()
try:
 code,land,body=core.page(SELECTED)
 out["publisher_http"]=code
 if code!=200:raise RuntimeError("PUBLISHER_SELECTED_HTTP_"+str(code))
 d=core.driver()
 try:
  try:d.get(SELECTED)
  except Exception:pass
  candidates={}
  duration=0
  for tick in range(24):
   try:
    videos=d.execute_script("""return [...document.querySelectorAll('main video,article video,#player video')].map(v=>({duration:Number.isFinite(v.duration)?v.duration:0,src:v.currentSrc||v.src||''}));""")
    durations=[float(v.get("duration") or 0) for v in videos if float(v.get("duration") or 0)>=90]
    if len(durations)==1:duration=durations[0]
    for log in d.get_log("performance"):
     event=json.loads(log["message"])["message"]
     if event.get("method")!="Network.responseReceived":continue
     packet=event.get("params",{});res=packet.get("response",{})
     url=res.get("url","");mime=res.get("mimeType","")
     if not core.safe_url(url):continue
     u=urllib.parse.urlsplit(url)
     if (u.path.lower().endswith(".m3u8") and
         mime in ("application/vnd.apple.mpegurl","application/x-mpegurl","audio/mpegurl") and
         int(res.get("status") or 0)==200 and
         not re.search(r"(?i)adserver|advert|preview|teaser|trailer|sample",u.path)):
      candidates[url]=mime
   except Exception:pass
   if len(candidates)==1 and duration>=90:break
   time.sleep(.55)
  out["selected_player_duration_s"]=round(duration,3)
  out["observed_hls_count"]=len(candidates)
  out["observed_hls_hosts"]=list(dict.fromkeys(urllib.parse.urlsplit(u).hostname for u in candidates))
 finally:d.quit()
 if duration<90 or len(candidates)!=1:
  raise RuntimeError("NO_UNIQUE_FULL_RUNTIME_SELECTED_HLS")
 media=next(iter(candidates))
 out["source_host"]=urllib.parse.urlsplit(media).hostname
 candidate={"source":media,"referer":SELECTED,"duration":duration,"reason":"one real selected article player duration and actual browser HLS"}
 out.update(core.full_file(SELECTED,candidate))
except Exception as error:out["reason"]=str(error)[:275]
out["elapsed_seconds"]=round(time.monotonic()-started,1)
print(json.dumps(out,sort_keys=True),flush=True)
Path("pc-dsav-complete-receipt.json").write_text(json.dumps(out,indent=2))
if not out.get("complete_selected_original"):sys.exit(2)
