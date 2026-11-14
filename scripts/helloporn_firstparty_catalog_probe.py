#!/usr/bin/env python3
"""Discover exact HelloPorn selected video URL in first-party card markup.
Only HTML. Redact tokens; no media bytes.
"""
import re,urllib.parse,urllib.request
URL="https://helloporn.com/"
with urllib.request.urlopen(urllib.request.Request(URL,headers={
 "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
 "Accept-Encoding":"identity"}),timeout=17) as r:txt=r.read(850000).decode("utf8","replace")
def red(s):
 s=re.sub(r"https?://[^ \t\n'\"<>]+",lambda m:"[HOST:"+str(urllib.parse.urlsplit(m.group()).hostname)+"]",s)
 s=re.sub(r"[A-Za-z0-9_-]{35,}","[ID]",s)
 return re.sub(r"\s+"," ",s)[:700]
print("chars",len(txt),flush=True)
for label,pat in [
 ("video_tag",r"<video\b"),("html_data_video",r"data-video-(?:id|url|src)"),
 ("player",r"id=[\"'](?:player|video-player)"),("watch_slug",r"href=[\"'][^\"']{2,180}(?:watch|videos|video)[^\"']*[\"']"),
 ("video_card",r"class=[\"'][^\"']*(?:video-card|video-item|video-thumb)[^\"']*[\"']"),
 ("JSON_video",r"\"videoUrl\"|\"contentUrl\"|\"video_url\""),
 ]:
    hits=list(re.finditer(pat,txt,re.I))
    print(label,"count",len(hits),flush=True)
    for m in hits[:2]:
        start=max(0,m.start()-220);end=min(len(txt),m.end()+450)
        print(" CONTEXT",red(txt[start:end]),flush=True)
