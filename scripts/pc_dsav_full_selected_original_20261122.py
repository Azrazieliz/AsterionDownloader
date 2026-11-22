#!/usr/bin/env python3
"""DSAV public selected article -> actual HLS -> complete playable file PC gate.

The selected publisher browser renders exactly one full-length main video.
Accept only its observed HLS playlist from the already witnessed CDN with
HTTP 200. Never guess signatures, bypass access controls, infer media from
a poster, or report a partial download as success. Media is deleted after
full byte/hash/FFprobe validation.
"""
import hashlib,json,re,subprocess,sys,tempfile,time,urllib.parse,urllib.request
from pathlib import Path
from pc_full_selected_provider_acceptance_20261122 import driver,safe_url,host

SELECTED="https://dsav123.wiki/video/2019329099956133888/"
OBSERVED_CDN="er45yer.sxtmo.com"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
HEADERS={"User-Agent":UA,"Referer":SELECTED,
         "Origin":"https://dsav123.wiki","Accept-Encoding":"identity"}

def manifest_probe(source):
    with urllib.request.urlopen(urllib.request.Request(source,headers=HEADERS),timeout=25) as res:
        text=res.read(300_000).decode("utf-8","replace")
        assert res.status==200 and text.startswith("#EXTM3U"),"SELECTED_HLS_NOT_PLAYLIST"
    lines=[line.strip() for line in text.splitlines()]
    out={"manifest_http":200,"selected_manifest_host":host(source),
         "hls_is_master":any(x.startswith("#EXT-X-STREAM-INF") for x in lines),
         "published_segment_count":sum(bool(x) and not x.startswith("#") for x in lines),
         "has_endlist":"#EXT-X-ENDLIST" in text,
         "playlist_duration_seconds":round(sum(float(x) for x in
             re.findall(r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",text)),2)}
    if not out["hls_is_master"]:
        # Fast evidence gate: no full 203-segment transfer when the actual
        # publisher-listed first, middle or final .ts is already HTTP403.
        # Signed URLs are consumed only as published by this exact HLS,
        # never synthesized, token-guessed or logged.
        segments=[x for x in lines if x and not x.startswith("#")]
        examined={}
        for label,index in (("first",0),("middle",len(segments)//2),
                            ("last",len(segments)-1)):
            if not segments:break
            link=urllib.parse.urljoin(source,segments[index])
            if not safe_url(link):
                examined[label]={"http":None,"problem":"unsafe published segment"}
                continue
            item={"host":host(link)}
            try:
                h=dict(HEADERS,Range="bytes=0-751")
                with urllib.request.urlopen(urllib.request.Request(link,headers=h),timeout=15) as r:
                    sample=r.read(752)
                    item.update(http=r.status,
                        mime=r.headers.get("Content-Type","").split(";")[0],
                        bytes_read=len(sample),
                        ts_sync=(len(sample)>=752 and
                            all(sample[i*188]==0x47 for i in range(4))))
            except urllib.error.HTTPError as e:
                item.update(http=e.code)
            except Exception as e:
                item.update(problem=type(e).__name__)
            examined[label]=item
        out["published_segment_checks"]=examined
    return out

def complete_dsav(source,expected_runtime):
    with tempfile.TemporaryDirectory(prefix="asterion-dsav-pc-full-") as folder:
        output=Path(folder)
        cmd=[sys.executable,"-m","yt_dlp","--no-playlist","--no-part",
            "--no-mtime","--no-warnings","--retries","3","--fragment-retries","3",
            "--concurrent-fragments","4","--socket-timeout","22",
            "--max-filesize","1200M","--format","best",
            "--user-agent",UA,
            "--add-header","Referer:"+SELECTED,
            "--add-header","Origin:https://dsav123.wiki",
            "-o",str(output/"selected-original.%(ext)s"),source]
        try:
            process=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
                                   text=True,timeout=400)
        except subprocess.TimeoutExpired:
            raise RuntimeError("FULL_SELECTED_DOWNLOAD_TIME_LIMIT_EXCEEDED")
        if process.returncode:
            tail=process.stdout[-750:]
            tail=re.sub(r"https?://\S+","[REDACTED]",tail)
            raise RuntimeError("ORIGINAL_TRANSFER_FAILED:"+tail)
        candidates=[f for f in output.iterdir() if f.is_file() and
                    f.stat().st_size>2_000_000 and not f.name.endswith(".part")]
        if len(candidates)!=1:raise RuntimeError("FULL_SELECTED_FILE_NOT_FINALIZED")
        path=candidates[0]
        probe=subprocess.run(["ffprobe","-v","error","-show_entries",
              "format=duration,size:stream=codec_type","-of","json",str(path)],
              capture_output=True,text=True,timeout=35)
        if probe.returncode:raise RuntimeError("FFPROBE_UNPLAYABLE_SELECTED_FILE")
        parsed=json.loads(probe.stdout)
        seconds=float(parsed.get("format",{}).get("duration") or 0)
        tracks=[v.get("codec_type") for v in parsed.get("streams",[])]
        if seconds<max(90,expected_runtime*.90) or seconds>expected_runtime*1.10:
            raise RuntimeError("WRONG_OR_PREVIEW_DURATION")
        if "audio" not in tracks or "video" not in tracks:
            raise RuntimeError("SELECTED_ORIGINAL_LACKS_AUDIO_OR_VIDEO")
        digest=hashlib.sha256()
        with path.open("rb") as stream:
            for chunk in iter(lambda:stream.read(1048576),b""):
                digest.update(chunk)
        return {"complete_selected_original":True,"bytes":path.stat().st_size,
                "duration_seconds":round(seconds,3),"tracks":tracks,
                "sha256":digest.hexdigest(),
                "verified_runtime_seconds":expected_runtime}

result={"provider":"dsav","selected_article":SELECTED,"selected_article_host":host(SELECTED),
        "complete_selected_original":False}
d=driver()
try:
    # This public Next/JS catalog initializes its browser session on /
    # before video navigation. A cold deep link produced an empty player,
    # whereas same-browser homepage -> selected article yielded HLS 200.
    try:d.get("https://dsav123.wiki/")
    except Exception:pass
    time.sleep(2)
    try:d.get(SELECTED)
    except Exception:pass
    time.sleep(3)
    try:d.execute_script("""const v=document.querySelector('main video,article video,video');
      if(v){v.scrollIntoView({block:'center'});const p=v.play();if(p?.catch)p.catch(()=>{});}""")
    except Exception:pass
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
    result.update(manifest_probe(source))
    checks=result.get("published_segment_checks",{})
    if len(checks)!=3 or any(
        x.get("http") not in (200,206) for x in checks.values()):
        raise RuntimeError("PUBLISHER_HLS_SEGMENTS_UNAVAILABLE_FIRST_MIDDLE_LAST")
    result.update(complete_dsav(source,max(durations)))
except Exception as exc:
    result["error_type"]=type(exc).__name__
    result["reason"]=re.sub(r"https?://\S+","[URL_REDACTED]",str(exc))[:250]
finally:
    d.quit()
    Path("pc-full-dsav-selected.json").write_text(json.dumps(result,indent=2,sort_keys=True))
    print(json.dumps(result,sort_keys=True),flush=True)
if not result["complete_selected_original"]:sys.exit(2)
