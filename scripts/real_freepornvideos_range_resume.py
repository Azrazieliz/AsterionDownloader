#!/usr/bin/env python3
"""Exact bundled yt-dlp + real FreePornVideos selected original range resume proof.

Two bounded 4KiB reads only; verifies overlapping source bytes at the current
public CDN, never calls a page HTML response a full video success.
"""
import json,re,sys,urllib.parse,urllib.request
from pathlib import Path
WHEEL=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
sys.path.insert(0,str(WHEEL))
import yt_dlp
PAGE="https://www.freepornvideos.xxx/videos/93823084/serena-gomes-a-savage-debut-in-a-gangbang-with-double-anal/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
def span(url,first,last):
    req=urllib.request.Request(url,headers={"Referer":PAGE,"User-Agent":UA,
        "Accept-Encoding":"identity","Range":f"bytes={first}-{last}"})
    with urllib.request.urlopen(req,timeout=25) as response:
        payload=response.read(4096+1)
        return {"status":response.status,"mime":response.headers.get("Content-Type","").split(";")[0],
            "etag":bool(response.headers.get("ETag")),"range":response.headers.get("Content-Range",""),
            "size":len(payload),"host":urllib.parse.urlsplit(response.url).hostname},payload
def main():
    assert str(yt_dlp.version.__version__)=="2026.10.26"
    opts={"quiet":True,"no_warnings":True,"skip_download":True,"noplaylist":True,
        "socket_timeout":20,"retries":0,"http_headers":{"User-Agent":UA,"Referer":PAGE}}
    with yt_dlp.YoutubeDL(opts) as instance:
        meta=instance.extract_info(PAGE,download=False)
    formats=[f for f in meta.get("formats",[]) if
        f.get("protocol") in ("http","https") and f.get("ext")=="mp4" and
        f.get("vcodec")!="none" and
        urllib.parse.urlsplit(f.get("url","")).hostname in
        ("freepornvideos.xxx","www.freepornvideos.xxx")]
    assert formats,"No first-party selected MP4 formats"
    chosen=min(formats,key=lambda f:int(f.get("height") or 0))
    url=chosen["url"]
    a,chunkA=span(url,0,4095)
    b,chunkB=span(url,4032,8127)
    first=re.fullmatch(r"bytes 0-4095/(\d+)",a["range"])
    second=re.fullmatch(r"bytes 4032-8127/(\d+)",b["range"])
    stable=(first is not None and second is not None and
        first.group(1)==second.group(1) and
        len(chunkA)==4096 and len(chunkB)==4096 and
        chunkA[4032:4096]==chunkB[0:64])
    print(json.dumps({"yt_dlp":yt_dlp.version.__version__,"formats":len(formats),
        "selected_height":chosen.get("height"),"first_status":a["status"],
        "second_status":b["status"],"mime":a["mime"],"etag":a["etag"],
        "total_bytes":int(first.group(1)) if first else -1,
        "first_ftyp":chunkA[4:8]==b"ftyp",
        "overlap_matches":stable,"first_host":a["host"],"second_host":b["host"],
        "bytes_read":len(chunkA)+len(chunkB),"classification":
        "RANGE_RESUME_VERIFIED_NOT_FULL_FILE" if stable else "RANGE_RESUME_FAILED"}),flush=True)
    return 0 if stable and a["status"]==206 and b["status"]==206 and chunkA[4:8]==b"ftyp" else 2
if __name__=="__main__":raise SystemExit(main())
