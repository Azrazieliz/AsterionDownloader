#!/usr/bin/env python3
"""Exact owner-selected JavGuru JK: whole finalized HLS with public HTTPS segments.

The production yt-dlp 2026.10.26 networking returned 403 on this selected
signed CDN even though ordinary HTTPS GET accepted the exact master, variant
and first MPEG-TS fragment. Run a bounded native Python HTTPS HLS transfer
against ONLY publisher-attested Maxstream segments. Never guess tokens or
replace the selected original. Final duration, audio/video and SHA required.
Temporary complete media is deleted on exit. Not an Android APK release.
"""
import concurrent.futures,hashlib,json,re,shutil,subprocess,sys,tempfile,time
import urllib.parse,urllib.request,urllib.error
from pathlib import Path
from verify_javguru_owner_jk_full_pc import selected_final
from inspect_javguru_jk_hls_finality import manifest,UA

HOST="s4.maxstream.org"
MAX_FRAGMENT_BYTES=15_000_000
MAX_FRAGMENTS=1800
MIN_FREE_BYTES=5_000_000_000

def selected_segments(media,master,expected):
    raw=manifest(media,master)
    if raw.count("#EXT-X-ENDLIST")!=1 or "#EXT-X-KEY:" in raw:
        raise RuntimeError("selected HLS not finalized or encrypted")
    if any(tag in raw for tag in ("#EXT-X-MAP:","#EXT-X-BYTERANGE:")):
        raise RuntimeError("selected HLS requires unsupported bounded segment assembly")
    listed=[v.strip() for v in raw.splitlines() if v.strip() and not v.startswith("#")]
    if len(listed)!=expected["media_segments"] or len(listed)>MAX_FRAGMENTS:
        raise RuntimeError("selected full segment count changed")
    duration=sum(float(x) for x in re.findall(
        r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",raw))
    if abs(duration-expected["runtime_s"])>1:
        raise RuntimeError("selected publisher runtime changed")
    result=[]
    for name in listed:
        if len(name)>2200 or ".." in urllib.parse.urlsplit(name).path:
            raise RuntimeError("untrusted media segment route")
        link=urllib.parse.urlsplit(urllib.parse.urljoin(media,name))
        if(link.scheme!="https" or link.hostname!=HOST or
           link.username or link.password or link.port or link.fragment or
           not re.search(r"\.(?:ts|m4s)(?:$)",link.path,re.I)):
            raise RuntimeError("media segment left exact publisher-signed player")
        result.append(link.geturl())
    if len(set(result))!=len(result):
        raise RuntimeError("media segment URL duplicated")
    return result,duration

def get_segment(item):
    index,link,target,referer=item
    head={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}
    last=None
    for attempt in range(3):
        try:
            req=urllib.request.Request(link,headers=head)
            with urllib.request.urlopen(req,timeout=30) as resp:
                if resp.status not in (200,206):
                    raise RuntimeError("publisher media HTTP not 200/206")
                content_type=resp.headers.get("Content-Type","").split(";",1)[0].lower()
                if content_type not in ("video/mp2t","application/octet-stream",
                         "binary/octet-stream","application/mp2t"):
                    raise RuntimeError("publisher segment is not MPEG transport data")
                size=0
                sha=hashlib.sha256()
                with target.open("wb") as f:
                    while True:
                        data=resp.read(512*1024)
                        if not data:break
                        size+=len(data)
                        if size>MAX_FRAGMENT_BYTES:
                            raise RuntimeError("selected media fragment exceeds bounded size")
                        sha.update(data)
                        f.write(data)
                if size<1500:
                    raise RuntimeError("publisher segment returned no playable media bytes")
                with target.open("rb") as f:
                    header=f.read(1024)
                if not (header[0:1]==b"\x47" or header[188:189]==b"\x47"):
                    raise RuntimeError("publisher segment has no MPEG-TS sync byte")
                return index,size,sha.hexdigest()
        except Exception as exc:
            last=type(exc).__name__
            target.unlink(missing_ok=True)
            if attempt<2:time.sleep(.4*(attempt+1))
    raise RuntimeError("publisher selected fragment transfer failed: "+str(last))

def main():
    embed,master,media,stats=selected_final()
    urls,published_duration=selected_segments(media,master,stats)
    print(json.dumps({"provider":"jav_guru","selected_server":"JK",
        "stage":"FINAL_SELECTED_NATIVE_HTTPS_SEGMENTS",
        "published_duration_s":round(published_duration,3),
        "segments":len(urls),"endlist":True,
        "full_original_verified":False}),flush=True)
    stage=Path("D:/AsterionControlRelease/diagnostics")
    if not stage.is_dir() or shutil.disk_usage(stage).free<MIN_FREE_BYTES:
        raise RuntimeError("guarded PC D drive insufficient for full original")
    now=time.monotonic()
    with tempfile.TemporaryDirectory(prefix="asterion_javguru_jk_https_complete_",dir=stage) as directory:
        root=Path(directory)
        paths=[root/("fragment-%04d.ts"%i) for i in range(len(urls))]
        bytes_done=0
        completed=0
        last_update=now
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            futures=[pool.submit(get_segment,(i,url,paths[i],media))
                     for i,url in enumerate(urls)]
            for future in concurrent.futures.as_completed(futures):
                index,size,digest=future.result()
                bytes_done+=size
                completed+=1
                clock=time.monotonic()
                if clock-last_update>45 or completed==len(urls):
                    last_update=clock
                    print(json.dumps({"provider":"jav_guru",
                      "stage":"SELECTED_FINAL_HTTPS_PROGRESS",
                      "finished_segments":completed,"total_segments":len(urls),
                      "downloaded_bytes":bytes_done,
                      "elapsed_s":round(clock-now,1),
                      "complete_verified":False}),flush=True)
        combined=root/"owner-selected-original.ts"
        sha=hashlib.sha256()
        with combined.open("wb") as output:
            for idx,item in enumerate(paths):
                if not item.is_file() or item.stat().st_size<1500:
                    raise RuntimeError("selected original has missing fragment")
                with item.open("rb") as f:
                    while True:
                        data=f.read(1024*1024)
                        if not data:break
                        output.write(data)
                        sha.update(data)
                item.unlink()
        length=combined.stat().st_size
        if length!=bytes_done or length<30_000_000:
            raise RuntimeError("assembled bytes did not match every real fragment")
        ffprobe=shutil.which("ffprobe")
        if not ffprobe:raise RuntimeError("ffprobe not present")
        result=subprocess.run([ffprobe,"-v","error",
                "-show_entries","format=duration:stream=codec_type,codec_name,duration,width,height",
                "-of","json",str(combined)],capture_output=True,text=True,timeout=60)
        if result.returncode!=0:raise RuntimeError("full media ffprobe failed")
        info=json.loads(result.stdout)
        duration=float(info.get("format",{}).get("duration") or 0)
        tracks=[s.get("codec_type") for s in info.get("streams",[])]
        valid=( "video" in tracks and "audio" in tracks and
            abs(duration-published_duration)<=max(5,0.01*published_duration))
        print(json.dumps({"provider":"jav_guru","stage":"SELECTED_JK_COMPLETE_FFPROBE",
          "bytes":length,"ffprobe_duration_s":round(duration,3),
          "publisher_final_duration_s":round(published_duration,3),
          "stream_kinds":tracks,
          "duration_match":valid,"all_fragments":completed==len(urls)}),flush=True)
        if not valid:raise RuntimeError("publisher final original failed full timeline/audio validation")
        print(json.dumps({"provider":"jav_guru",
            "status":"COMPLETE_SELECTED_PC_FILE",
            "selected_server":"JK",
            "bytes":length,"duration_s":round(duration,3),
            "published_duration_s":round(published_duration,3),
            "sha256_prefix":sha.hexdigest()[:24],
            "video":True,"audio":True,
            "android_receipt_verified":False,
            "temp_media_retained":False}),flush=True)
    return 0

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","selected_server":"JK",
          "status":"NOT_COMPLETE","error_type":type(e).__name__,
          "reason":re.sub(r"https?://\S+","[source-url]",str(e))[:140],
          "full_original_verified":False}),flush=True)
        sys.exit(3)
