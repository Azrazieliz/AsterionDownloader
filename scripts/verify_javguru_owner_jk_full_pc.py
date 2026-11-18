#!/usr/bin/env python3
"""Strict COMPLETE PC original: owner-selected JavGuru MXGS-1452 STREAM JK.

Selected post -> STREAM JK shortcode -> first-party /searcho/ -> exact
Maxstream player -> its own P.A.C.K.E.R file -> signed s4.maxstream.org HLS.
The child VOD must declare ENDLIST and a full duration. Download all real
fragments with the app's bundled yt-dlp; verify nonzero final bytes, FFprobe
video+audio, duration matching the final signed playlist, and SHA256.
Never accept an ad or preview, store long-term media, or build an APK.
"""
import hashlib,json,re,shutil,subprocess,sys,tempfile,time,urllib.parse
from pathlib import Path
from inspect_javguru_other_verified_servers import go,UA
from inspect_javguru_jk_hls_finality import manifest,shape

WHEEL=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"

def selected_final():
    embed,urls=go("JK",return_selected=True)
    if len(urls)!=1:raise RuntimeError("owner-selected JK source ambiguous")
    source=urls[0]
    p=urllib.parse.urlsplit(source)
    if(p.scheme!="https" or p.hostname!="s4.maxstream.org" or
       not p.path.endswith(".m3u8") or not p.query):
        raise RuntimeError("selected packed JK signed original unavailable")
    root=manifest(source,embed)
    lines=[v.strip() for v in root.splitlines()]
    renditions=[]
    for ix,line in enumerate(lines[:-1]):
        if not line.startswith("#EXT-X-STREAM-INF:"):continue
        if not re.search(r"RESOLUTION=[0-9]+x720(?:,|$)",line):
            continue
        target=urllib.parse.urljoin(source,lines[ix+1])
        u=urllib.parse.urlsplit(target)
        if u.scheme!="https" or u.hostname!="s4.maxstream.org" or not u.path.endswith(".m3u8"):
            continue
        stats=shape(manifest(target,source))
        if(stats["endlist"] and stats["runtime_s"]>3600 and
           stats["media_segments"]>100 and stats["stream_variants"]==0):
            renditions.append((target,stats))
    if len(renditions)!=1:raise RuntimeError("no unique finalized publisher 720p full original")
    return embed,source,renditions[0][0],renditions[0][1]

def main():
    embed,master,media,stats=selected_final()
    print(json.dumps({"provider":"jav_guru","stage":"PUBLISHER_FINAL_JK_SELECTED",
        "height":720,"playlist_duration_s":stats["runtime_s"],
        "segments":stats["media_segments"],"has_endlist":stats["endlist"],
        "selected_server":"JK","full_file_verified":False}),flush=True)
    if not WHEEL.is_file():raise RuntimeError("production extractor wheel missing")
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    if yt_dlp.version.__version__!="2026.10.26":
        raise RuntimeError("wrong embedded extractor version")
    stage=Path("D:/AsterionControlRelease/diagnostics")
    if not stage.is_dir() or shutil.disk_usage(stage).free<3_000_000_000:
        raise RuntimeError("safe D staging requires 3GB free; no C Temp")
    started=time.monotonic()
    printed=[started]
    def progress(item):
        if item.get("status") not in ("downloading","finished"):return
        now=time.monotonic()
        if item.get("status")!="finished" and now-printed[0]<80:return
        printed[0]=now
        print(json.dumps({"provider":"jav_guru","stage":"FULL_SELECTED_JK_DOWNLOAD",
            "status":item.get("status"),"bytes":int(item.get("downloaded_bytes") or 0),
            "elapsed_s":round(now-started,2),"verified":False}),flush=True)
    with tempfile.TemporaryDirectory(prefix="asterion_javguru_jk_full_",dir=stage) as directory:
        folder=Path(directory)
        opts={"quiet":True,"no_warnings":True,
              "noplaylist":True,"format":"best",
              "hls_use_mpegts":True,
              "outtmpl":str(folder/"selected-original.%(ext)s"),
              "http_headers":{"User-Agent":UA,"Referer":embed},
              "socket_timeout":25,"retries":1,"fragment_retries":2,
              "skip_unavailable_fragments":False,
              "concurrent_fragment_downloads":8,
              "progress_hooks":[progress]}
        with yt_dlp.YoutubeDL(opts) as client:
            client.download([media])
        files=[f for f in folder.iterdir() if f.is_file() and
               f.suffix.lower() in (".ts",".mp4",".mkv",".webm")]
        if len(files)!=1:raise RuntimeError("exact selected HLS yielded no unique final media")
        movie=files[0]
        length=movie.stat().st_size
        if length<30_000_000:raise RuntimeError("downloaded media too small")
        probe=shutil.which("ffprobe")
        if not probe:raise RuntimeError("ffprobe missing")
        r=subprocess.run([probe,"-v","error",
             "-show_entries","format=duration,format_name:stream=codec_type,duration,codec_name,width,height",
             "-of","json",str(movie)],capture_output=True,text=True,timeout=50)
        if r.returncode!=0:raise RuntimeError("full selected file failed ffprobe")
        data=json.loads(r.stdout)
        duration=float(data.get("format",{}).get("duration") or 0)
        tracks=[str(s.get("codec_type") or "") for s in data.get("streams",[])]
        print(json.dumps({"provider":"jav_guru","stage":"FULL_SELECTED_JK_FFPROBE",
            "bytes":length,"duration_s":round(duration,3),
            "published_duration_s":stats["runtime_s"],
            "format":data.get("format",{}).get("format_name"),
            "streams":tracks,"audio":("audio" in tracks),
            "video":("video" in tracks),
            "duration_matches":abs(duration-stats["runtime_s"])<=max(5,stats["runtime_s"]*.01),
            "full_original_verified":False}),flush=True)
        if "video" not in tracks or "audio" not in tracks or not (
            abs(duration-stats["runtime_s"])<=max(5,stats["runtime_s"]*.01)):
            raise RuntimeError("full JK original duration/audio did not match finalized source")
        sha=hashlib.sha256()
        with movie.open("rb") as fd:
            while chunk:=fd.read(1024*1024):sha.update(chunk)
        print(json.dumps({"provider":"jav_guru",
            "status":"COMPLETE_SELECTED_PC_FILE","selected_server":"JK",
            "bytes":length,"duration_s":round(duration,3),
            "published_duration_s":stats["runtime_s"],
            "sha256_prefix":sha.hexdigest()[:24],
            "video":True,"audio":True,
            "android_receipt_verified":False,
            "media_retained":False}),flush=True)
    return 0

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as exc:
        print(json.dumps({"provider":"jav_guru","selected_server":"JK",
            "status":"NOT_COMPLETE","error_type":type(exc).__name__,
            "reason":re.sub(r"https?://\S+","[source-url]",str(exc))[:150],
            "full_original_verified":False}),flush=True)
        sys.exit(3)
