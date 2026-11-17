#!/usr/bin/env python3
"""Read-only safe shape of selected MXGS-1452 javclan packed player source.

No JS execution, URLs/token exposure, unrelated player scans, or media save.
"""
import json,re,urllib.parse
from verify_javguru_owner_sb_full_pc import selected_player
from verify_roshy_selected_packed_hls import Script,deobfuscate
def main():
    embed,page=selected_player()
    if urllib.parse.urlsplit(embed).hostname!="javclan.com":
        raise ValueError("Selected SB host changed")
    scan=Script();scan.feed(page)
    total=0
    for n,raw in enumerate(scan.raw):
        if "eval(function(p,a,c,k,e,d)" not in raw:continue
        text=deobfuscate(raw)
        if not text:continue
        total+=1
        def safe(segment):
            segment=re.sub(r"https?://[^\s\"'<>\\]{5,2500}","[https-media]",segment)
            segment=re.sub(r"[A-Za-z0-9_/+=.-]{30,}","[opaque]",segment)
            return re.sub(r"\s+"," ",segment)[:180]
        keys=("hls4","hls3","hls2","file","sources","jwplayer",".m3u8",
              "stream","atob","decode","videojs","player")
        contexts={}
        for key in keys:
            m=re.search(re.escape(key),text,re.I)
            if m:contexts[key]=safe(text[max(0,m.start()-45):m.end()+100])
        urls=[]
        for m in re.finditer(r"https?(?::|\\:)//[^\s\"'<>]{5,2500}",text,re.I):
            u=urllib.parse.urlsplit(m.group().replace("\\/","/"))
            if u.hostname:
                urls.append({"host":u.hostname,"path_extension":
                    u.path.rsplit(".",1)[-1][:8],"has_query":bool(u.query)})
        print(json.dumps({"stage":"EXACT_SB_DECODED_SHAPE",
            "decoded_index":n,"decoded_chars":len(text),
            "counts":{x:text.lower().count(x.lower()) for x in keys},
            "source_contexts":contexts,"url_hosts_and_extensions":urls[:12],
            "remote_hls_verified":False}),flush=True)
    print(json.dumps({"stage":"SELECTED_SB_PACKED_SUMMARY",
        "scripts_decoded":total,"html_chars":len(page)}),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as e:
        print(json.dumps({"status":"SELECTED_SB_UNVERIFIED",
            "error_type":type(e).__name__,
            "message":str(e)[:110]}),flush=True)
        raise SystemExit(2)
