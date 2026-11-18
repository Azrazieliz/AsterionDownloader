#!/usr/bin/env python3
"""Test standard publisher HLS referer contract for selected JavGuru JK source.

This source is attributed only to the user's selected post via official
first-party shortcode and packed player. Test one genuine selected 720p
media segment; never bypass challenge, invent source or print signed tokens.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from verify_javguru_owner_jk_full_pc import selected_final
from inspect_javguru_jk_hls_finality import manifest,UA
def probe(url,ref):
    headers={"User-Agent":UA,"Referer":ref,
             "Range":"bytes=0-4095","Accept-Encoding":"identity"}
    req=urllib.request.Request(url,headers=headers)
    try:
        with urllib.request.urlopen(req,timeout=25) as r:
            sample=r.read(4096)
            return {"http":r.status,"bytes":len(sample),
                    "type":r.headers.get("Content-Type","").split(";")[0]}
    except urllib.error.HTTPError as e:
        return {"http":e.code,"bytes":0}
    except Exception as e:
        return {"error_type":type(e).__name__,"bytes":0}
def main():
    embed,master,child,stats=selected_final()
    m=manifest(child,master)
    seg=[v.strip() for v in m.splitlines() if v.strip() and not v.startswith("#")]
    if len(seg)!=stats["media_segments"]:raise RuntimeError("final source changed")
    line=next((v for v in seg if v.endswith(".ts") or ".ts?" in v or
                v.endswith(".m4s") or ".m4s?" in v),None)
    if not line:raise RuntimeError("no expected real segment path")
    video=urllib.parse.urljoin(child,line)
    p=urllib.parse.urlsplit(video)
    if p.scheme!="https" or p.hostname!="s4.maxstream.org" or p.username or p.password:
        raise RuntimeError("media segment left approved selected host")
    print(json.dumps({"provider":"jav_guru","stage":"JK_SIGNED_PUBLIC_HLS_REFERER",
        "full_source_duration_s":stats["runtime_s"],
        "endlist":stats["endlist"],
        "master_referer":probe(child,master),
        "player_referer":probe(child,embed),
        "segment_master_referer":probe(video,master),
        "segment_variant_referer":probe(video,child),
        "segment_player_referer":probe(video,embed),
        "complete_file_verified":False}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","stage":"JK_REFERER_ACCESS_UNVERIFIED",
            "error_class":type(e).__name__}),flush=True)
        sys.exit(3)
