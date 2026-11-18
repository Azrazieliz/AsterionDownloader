#!/usr/bin/env python3
"""Exact selected Jav Guru SB HLS transport diagnosis after 600-second full-file timeout.

The already verified MXGS-1452 STREAM SB source is followed through its
publisher-provided first-party shortcode and signed premilkyway master.
Reads manifest text plus up to 4096 bytes from ONE manifest-declared segment.
No guessed URLs, cross-host redirects, or alternative scenes; no full-file
success claims, no cookies or tokens printed.
"""
import json, re, time, urllib.error, urllib.parse, urllib.request
from verify_javguru_owner_sb_full_pc import selected_player, selected_hls, UA
ALLOWED_SUFFIX=".premilkyway.com"
def request(url,ref,bytes_limit=110000):
    t=time.monotonic()
    headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"}
    # A public range is diagnostic only; never count this as a complete file.
    if bytes_limit==4096:headers["Range"]="bytes=0-4095"
    req=urllib.request.Request(url,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=22) as r:
            raw=r.read(bytes_limit)
            return {"http":r.status,"mime":r.headers.get("Content-Type","").split(";")[0],
                "bytes":len(raw),"elapsed_s":round(time.monotonic()-t,2)},raw
    except urllib.error.HTTPError as e:
        return {"http":e.code,"elapsed_s":round(time.monotonic()-t,2)},b""
    except Exception as e:
        return {"error_type":type(e).__name__,
            "elapsed_s":round(time.monotonic()-t,2)},b""
def safe_child(base,raw):
    if len(raw)>2400 or raw.startswith("#"):return None
    b=urllib.parse.urlsplit(base)
    u=urllib.parse.urlsplit(urllib.parse.urljoin(base,raw))
    if (u.scheme!="https" or not u.hostname or
        not u.hostname.endswith(ALLOWED_SUFFIX) or
        b.hostname!=u.hostname or u.username or u.password or
        u.port or u.fragment):
        return None
    if not (u.path.endswith(".m3u8") or re.search(r"\.(?:ts|m4s|mp4|aac)(?:$)",u.path,re.I)):
        return None
    return u.geturl()
def main():
    embed,player=selected_player()
    master=selected_hls(embed,player)
    if not master:raise RuntimeError("selected signed SB source missing")
    detail,content=request(master,embed)
    text=content.decode("utf8","replace")
    valid=text.lstrip().startswith("#EXTM3U")
    lines=[l.strip() for l in text.splitlines() if l.strip() and not l.startswith("#")]
    children=[safe_child(master,line) for line in lines[:40]]
    children=list(dict.fromkeys(x for x in children if x))
    print(json.dumps({"stage":"EXACT_SB_MASTER",
        "status":detail,"extm3u":valid,"declared_child_count":len(lines),
        "source_host":urllib.parse.urlsplit(master).hostname,
        "child_count":len(children)}),flush=True)
    if not valid or not children:return 2
    child=children[0]
    if urllib.parse.urlsplit(child).path.endswith(".m3u8"):
        detail,body=request(child,master)
        inner=body.decode("utf8","replace")
        fragments=[l.strip() for l in inner.splitlines() if l.strip() and not l.startswith("#")]
        candidates=[safe_child(child,l) for l in fragments[:80]]
        candidates=[x for x in candidates if x]
        durations=[float(s) for s in re.findall(r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",inner)]
        print(json.dumps({"stage":"EXACT_SB_VARIANT",
            "status":detail,"extm3u":inner.lstrip().startswith("#EXTM3U"),
            "published_fragment_count":len(fragments),
            "sampled_playlist_duration_s":round(sum(durations),1),
            "first_party_segment_candidates":len(candidates)}),flush=True)
        if not candidates:return 3
        child=candidates[0]
    detail,body=request(child,master,4096)
    print(json.dumps({"stage":"EXACT_SB_FIRST_MEDIA_FRAGMENT",
        "status":detail,"has_media_bytes":len(body)>0,
        "bytes":len(body),"full_original_verified":False}),flush=True)
    return 0 if detail.get("http") in (200,206) and len(body)>500 else 4
if __name__=="__main__":
    try:raise SystemExit(main())
    except Exception as e:
        print(json.dumps({"stage":"SB_TRANSPORT_NOT_VERIFIED",
          "error_type":type(e).__name__,"full_original_verified":False}),flush=True)
        raise SystemExit(5)
