#!/usr/bin/env python3
"""DSAV public selected article -> actual HLS -> complete playable file PC gate.

The selected publisher browser renders exactly one full-length main video.
Accept only its observed HLS playlist from the already witnessed CDN with
HTTP 200. Never guess signatures, bypass access controls, infer media from
a poster, or report a partial download as success. Media is deleted after
full byte/hash/FFprobe validation.
"""
import hashlib,json,re,sys,time,urllib.parse
from pathlib import Path
from pc_full_selected_provider_acceptance_20261122 import driver,full_file,safe_url,host

SELECTED="https://dsav123.wiki/video/2019329099956133888/"
OBSERVED_CDN="er45yer.sxtmo.com"
result={"provider":"dsav","selected_article":SELECTED,"selected_article_host":host(SELECTED),
        "complete_selected_original":False}
d=driver()
try:
    d.get(SELECTED)
except Exception:
    pass
try:
    playlists=set()
    durations=[]
    encountered={}
    for i in range(38):
        try:
            current=d.execute_script("""return (function(){
                const videos=[...document.querySelectorAll(
                  'main video,article video,#player video,.video-player video,video')];
                return videos.filter(v=>!v.closest(
                  'aside,.related,.recommended,.ads,.advertisement,[data-ad]'))
                  .map(v=>({duration:Number.isFinite(v.duration)?
                    Math.round(v.duration):0,
                    hasMedia:!!(v.currentSrc||v.src||v.querySelector('source[src]')?.src),
                    ready:v.readyState})).slice(0,12);
            })()""") or []
            durations.extend(int(x.get("duration") or 0) for x in current
                             if 90<=int(x.get("duration") or 0)<=12*3600)
        except Exception:
            pass
        try:
            for entry in d.get_log("performance"):
                packet=json.loads(entry["message"])["message"]
                if packet.get("method")!="Network.responseReceived":continue
                params=packet.get("params",{})
                response=params.get("response",{})
                media=response.get("url","")
                p=urllib.parse.urlsplit(media)
                if not safe_url(media) or p.hostname!=OBSERVED_CDN:continue
                mime=response.get("mimeType","").lower().split(";")[0]
                code=int(response.get("status") or 0)
                if code!=200:continue
                if mime not in ("application/vnd.apple.mpegurl",
                               "application/x-mpegurl") and not p.path.lower().endswith(".m3u8"):
                    continue
                if re.search("preview|trailer|teaser|sample",p.path,re.I):continue
                playlists.add(media)
                encountered[host(media)]=code
        except Exception:
            pass
        if len(playlists)==1 and durations:break
        time.sleep(.7)
    result["browser_main_video_runtime_seconds"]=max(durations) if durations else None
    result["candidate_manifest_count"]=len(playlists)
    result["observed_hls_hosts"]=list(encountered)
    if len(playlists)!=1 or not durations:
        raise RuntimeError("NO_UNIQUE_SELECTED_HLS_WITH_FULL_MAIN_PLAYER_DURATION")
    source=next(iter(playlists))
    # This is a representative article, not another item from recommendations.
    candidate={"source":source,"referer":SELECTED,
               "duration":max(durations),
               "reason":"one first-party selected article, live main-player duration and exactly one observed HLS"}
    result.update(full_file(SELECTED,candidate))
except Exception as exc:
    result["error_type"]=type(exc).__name__
    result["reason"]=re.sub(r"https?://\S+","[URL_REDACTED]",str(exc))[:250]
finally:
    d.quit()
    Path("pc-full-dsav-selected.json").write_text(json.dumps(result,indent=2,sort_keys=True))
    print(json.dumps(result,sort_keys=True),flush=True)
if not result["complete_selected_original"]:sys.exit(2)
