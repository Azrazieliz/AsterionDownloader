#!/usr/bin/env python3
"""One full real public selected FreePornVideos MP4; remove immediately.

All media and signed URLs stay within the ephemeral CI container. JSON reports
only actual bytes, matching lengths, MP4 playback duration and deletion.
No cookies, account access, DRM bypass, or preview substitution.
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import yt_dlp

PAGE="https://www.freepornvideos.xxx/videos/93823084/serena-gomes-a-savage-debut-in-a-gangbang-with-double-anal/"
MAX_BYTES=512*1024*1024
out={"publisher":"FreePornVideos","selected_page_host":"www.freepornvideos.xxx",
     "source":"user-selected public video","status":"not_started","media_retained":False}
opts={"quiet":True,"no_warnings":True,"noplaylist":True,"skip_download":True,
      "socket_timeout":20,"retries":0,"http_headers":{
       "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
       "Referer":PAGE}}
try:
    with yt_dlp.YoutubeDL(opts) as ydl:
        metadata=ydl.extract_info(PAGE,download=False)
    formats=metadata.get("formats",[])
    choices=sorted((f for f in formats if
        f.get("protocol") in ("https","http") and f.get("ext")=="mp4"
        and f.get("vcodec")!="none" and
        urllib.parse.urlsplit(f.get("url","")).scheme=="https" and
        urllib.parse.urlsplit(f.get("url","")).hostname in
            ("freepornvideos.xxx","www.freepornvideos.xxx")),
        key=lambda f:int(f.get("height") or 0))
    if not choices:
        raise RuntimeError("No verified public first-party MP4 format")
    media=choices[0]["url"]
    out["selected_height"]=choices[0].get("height")
    headers={"Referer":PAGE,"User-Agent":opts["http_headers"]["User-Agent"],
             "Accept":"video/mp4","Accept-Encoding":"identity"}
    with tempfile.TemporaryDirectory(prefix="asterion-fpv-acceptance-") as folder:
        dest=os.path.join(folder,"selected-full-original.mp4")
        request=urllib.request.Request(media,headers=headers)
        with urllib.request.urlopen(request,timeout=30) as response:
            out["http"]=response.status
            mime=response.headers.get("Content-Type","").split(";",1)[0].strip().lower()
            if response.status not in (200,206) or mime!="video/mp4":
                raise RuntimeError("Selected original returned non-MP4 HTTP response")
            declared=int(response.headers.get("Content-Length","-1"))
            if declared<=0 or declared>MAX_BYTES:
                raise RuntimeError("Original size unknown or above 512MiB test cap")
            out["expected_bytes"]=declared
            start=time.monotonic()
            count=0
            with open(dest,"wb") as target:
                while True:
                    if time.monotonic()-start>390:
                        raise RuntimeError("Full transfer exceeded 390-second test deadline")
                    chunk=response.read(128*1024)
                    if not chunk:
                        break
                    target.write(chunk)
                    count+=len(chunk)
                    if count>MAX_BYTES:
                        raise RuntimeError("Original exceeded byte cap")
            out["received_bytes"]=count
        if count!=declared:
            raise RuntimeError("Full original length mismatch")
        with open(dest,"rb") as f:
            magic=f.read(12)
        if len(magic)!=12 or magic[4:8]!=b"ftyp":
            raise RuntimeError("Received file is not an MP4 container")
        tool=shutil.which("ffprobe")
        if not tool:
            raise RuntimeError("ffprobe unavailable for actual media validation")
        probe=subprocess.run([tool,"-v","error","-show_entries",
            "format=duration,format_name","-of","json",dest],
            capture_output=True,text=True,timeout=30)
        if probe.returncode!=0:
            raise RuntimeError("Local file failed ffprobe playback parsing")
        parsed=json.loads(probe.stdout).get("format",{})
        duration=float(parsed.get("duration") or 0)
        if duration<=60:
            raise RuntimeError("Only an incomplete or preview recording obtained")
        out["duration_s"]=round(duration,2)
        out["format"]=parsed.get("format_name","")
        out["status"]="verified_complete_media_transfer"
        out["elapsed_s"]=round(time.monotonic()-start,2)
    out["media_retained"]=os.path.exists(dest)
except Exception as ex:
    out["status"]="blocked"
    out["failure"]=type(ex).__name__+": "+str(ex)[:170]
with open("real-freepornvideos-acceptance.json","w",encoding="utf-8") as f:
    json.dump(out,f,indent=2)
print(json.dumps(out,indent=2))
