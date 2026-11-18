#!/usr/bin/env python3
"""Inspect exactly the selected JavGuru STREAM SB quality/size tradeoffs.

Publisher post MXGS-1452 -> same-post SB shortcode -> first-party signed
player -> the publisher attested CDN master. No ads, previews, guessed URLs,
or media download. This only describes available full-length HLS variants;
it does not establish an original-complete receipt.
"""
import json,re,urllib.parse,urllib.request,urllib.error,time
from verify_javguru_owner_sb_full_pc import selected_player,selected_hls,UA
SUFFIX=".premilkyway.com"
def read(url,ref):
    u=urllib.parse.urlsplit(url)
    if u.scheme!="https" or not u.hostname or not u.hostname.endswith(SUFFIX):
        raise ValueError("Only publisher-selected CDN is allowed")
    req=urllib.request.Request(url,headers={"User-Agent":UA,
        "Referer":ref,"Accept-Encoding":"identity"})
    started=time.monotonic()
    with urllib.request.urlopen(req,timeout=25) as res:
        data=res.read(170000)
        if res.status!=200 or not data.startswith(b"#EXTM3U"):
            raise RuntimeError("Selected signed manifest is not HLS")
    return data.decode("utf8","replace"),round(time.monotonic()-started,2)
def main():
    embed,page=selected_player()
    master=selected_hls(embed,page)
    if not master:raise RuntimeError("Selected publisher SB HLS unavailable")
    manifest,elapsed=read(master,embed)
    lines=[s.strip() for s in manifest.splitlines()]
    variants=[]
    for i,line in enumerate(lines):
        if not line.startswith("#EXT-X-STREAM-INF"):continue
        if i+1>=len(lines):continue
        match=re.search(r"\bBANDWIDTH=([0-9]+)",line)
        resolution=re.search(r"\bRESOLUTION=([0-9]+)x([0-9]+)",line)
        source=urllib.parse.urljoin(master,lines[i+1])
        u=urllib.parse.urlsplit(source)
        if u.hostname!=urllib.parse.urlsplit(master).hostname or not u.path.endswith(".m3u8"):
            continue
        try:
            inner,t=read(source,master)
            count=sum(bool(s) and not s.startswith("#") for s in inner.splitlines())
            duration=sum(float(v) for v in re.findall(r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",inner))
            variants.append({"bitrate_bps":int(match.group(1)) if match else None,
                 "width":int(resolution.group(1)) if resolution else None,
                 "height":int(resolution.group(2)) if resolution else None,
                 "segment_count":count,"duration_s":round(duration,1),
                 "manifest_response_s":t,"valid_length":duration>600,"same_publisher":True})
        except Exception as err:
            variants.append({"resolution":resolution.group(0) if resolution else "",
                "error_class":type(err).__name__,"valid_length":False})
    print(json.dumps({"provider":"jav_guru","stage":"EXACT_SB_PUBLIC_HLS_RENDITIONS",
        "master_response_s":elapsed,
        "variants":variants,
        "all_full_originals_verified":False,
        "has_final_file_receipt":False}),flush=True)
    return 0 if any(v.get("valid_length") for v in variants) else 3
if __name__=="__main__":
    try:raise SystemExit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","stage":"SELECTED_VARIANTS_NOT_VERIFIED",
            "error_type":type(e).__name__}),flush=True)
        raise SystemExit(4)
