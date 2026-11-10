#!/usr/bin/env python3
"""Bounded WatchPorn page diagnostics. No DRM, credentials or saved media."""
import re, urllib.request, urllib.parse
from html.parser import HTMLParser

def get(url,method="GET",ref=None):
    headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"}
    if ref:headers["Referer"]=ref
    req=urllib.request.Request(url,headers=headers,method=method)
    with urllib.request.urlopen(req,timeout=18) as res:
        if method=="HEAD":return dict(res.headers)
        return res.read(2_000_000).decode("utf-8",errors="replace")
home=get("https://watchporn.to/")
match=re.search(r'href=["\'](?:https?://(?:www\.)?watchporn\.to)?(/video/\d+/[^"\']+)["\']',home)
if not match:raise SystemExit("NO_VIDEO_PAGE")
page="https://watchporn.to"+match.group(1)
html=get(page)
print("POST_HTML_LENGTH",len(html))
for k in ("duration","video_url","video_alt_url","<video","videoDuration","runtime","contentDuration"):
    print("PRESENT",k,int(k.lower() in html.lower()))
for match in re.finditer(r"(?:video_alt_url|video_url)\s*:\s*'([^']+)'",html):
    url=match.group(1).replace("&amp;","&")
    parsed=urllib.parse.urlsplit(url)
    print("SOURCE_HOST",parsed.hostname,"PATH_EXT",parsed.path.split(".")[-1],
          "SIGNED_QUERY",bool(parsed.query))
    if parsed.scheme=="https" and parsed.hostname=="watchporn.to":
        try:
            h=get(url,"HEAD",page)
            print("HEAD_CONTENT_TYPE",h.get("Content-Type",""),
                  "HEAD_CONTENT_LENGTH",h.get("Content-Length",""))
        except Exception as e:print("HEAD_ERROR",type(e).__name__,str(e)[:100])
for match in list(re.finditer(r"(?i)(?:duration|runtime|length)[^<\n\r]{0,45}",html))[:12]:
    value=re.sub(r"https?://\S+","[URL]",match.group(0))
    print("DURATION_CONTEXT",value[:90])
