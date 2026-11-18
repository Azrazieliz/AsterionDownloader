#!/usr/bin/env python3
"""Only owner's JavGuru MXGS-1452 STREAM JK -> Maxstream HLS finality check.

The exact first-party shortcode and selected packed player file assignment
attest the source. Do not infer a related file, ad URL, preview or CAPTCHA
token. This script downloads NO video bytes and prints no signed URLs.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from inspect_javguru_other_verified_servers import go,UA
def manifest(url,ref):
    p=urllib.parse.urlsplit(url)
    if p.scheme!="https" or not p.hostname or not p.hostname.endswith(".maxstream.org"):
        raise RuntimeError("selected signed player manifest is not Maxstream")
    req=urllib.request.Request(url,headers={"User-Agent":UA,
          "Referer":ref,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(req,timeout=25) as resp:
        source=urllib.parse.urlsplit(resp.url)
        if resp.status!=200 or not source.hostname or not source.hostname.endswith(".maxstream.org"):
            raise RuntimeError("selected playlist redirected outside approved publisher hoster")
        raw=resp.read(300000).decode("utf8","replace")
        if not raw.startswith("#EXTM3U"):
            raise RuntimeError("selected source is not HLS")
        return raw
def shape(text):
    return {"endlist":"#EXT-X-ENDLIST" in text,
       "playlist_types":re.findall(r"#EXT-X-PLAYLIST-TYPE:([A-Z]+)",text)[:3],
       "media_segments":sum(bool(s) and not s.startswith("#") for s in text.splitlines()),
       "runtime_s":round(sum(float(x) for x in re.findall(
           r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",text)),3),
       "stream_variants":text.count("#EXT-X-STREAM-INF:"),
       "target_duration":re.findall(r"#EXT-X-TARGETDURATION:([0-9]+)",text)[:2]}
def main():
    embed,urls=go("JK",return_selected=True)
    if len(urls)!=1:raise RuntimeError("selected published JK original is not unique")
    selected=urls[0]
    root=manifest(selected,embed)
    lines=[v.strip() for v in root.splitlines()]
    qualities=[]
    for ix,line in enumerate(lines[:-1]):
        if not line.startswith("#EXT-X-STREAM-INF:"):continue
        child=urllib.parse.urljoin(selected,lines[ix+1])
        p=urllib.parse.urlsplit(child)
        if p.scheme!="https" or not p.hostname or not p.hostname.endswith(".maxstream.org") or not p.path.endswith(".m3u8"):
            continue
        meta=re.search(r"RESOLUTION=[0-9]+x([0-9]+)",line)
        raw=manifest(child,selected)
        qualities.append({"height":int(meta.group(1)) if meta else None,
           "bandwidth_bps":int(re.search(r"BANDWIDTH=([0-9]+)",line).group(1)) if re.search(r"BANDWIDTH=([0-9]+)",line) else None,
           **shape(raw)})
    if not qualities:
        qualities=[{"height":None,**shape(root)}]
    print(json.dumps({"provider":"jav_guru","selected_server":"JK",
       "stage":"MAXSTREAM_OFFICIAL_SELECTED_HLS_FINALITY",
       "published_master":shape(root),"renditions":qualities[:8],
       "has_stable_final_video":any(x["endlist"] and x["runtime_s"]>600 for x in qualities),
       "complete_original_verified":False}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","selected_server":"JK",
           "status":"SELECTED_FINAL_MANIFEST_UNVERIFIED",
           "error_type":type(e).__name__}),flush=True)
        sys.exit(4)
