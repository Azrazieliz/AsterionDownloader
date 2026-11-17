#!/usr/bin/env python3
"""Inspect selected video's publisher player configuration for poster references."""
import re,json,urllib.parse,urllib.request,html as h
PAGE="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
with urllib.request.urlopen(urllib.request.Request(PAGE,headers={
    "User-Agent":UA,"Accept-Encoding":"identity"}),timeout=22) as res:
    html=res.read(1800000).decode("utf-8","replace")
def scrub(s):
    s=re.sub(r'https?://[^\s\'"<>]{15,250}',lambda m:urllib.parse.urlsplit(m.group(0)).hostname+" {URL}",s)
    s=re.sub(r'[A-Za-z0-9_-]{70,}','{token}',s)
    return re.sub(r'\s+',' ',s).strip()[:280]
for marker in ("preview_url","poster","screenshot","flashvars",
               "video_id","image_url","thumb_url","video_preview","kt_player",
               "video_url","videoPlayer","screenshots","<video"):
    matches=list(re.finditer(re.escape(marker),html,re.I))
    print("MARKER",marker,"COUNT",len(matches),flush=True)
    for m in matches[:4]:
        print("CTX",marker,scrub(html[max(0,m.start()-105):m.end()+190]),flush=True)
# URL-like values observed literally in first-party script configs
for expr in (r'(?i)[\'"\w](?:preview_url|poster_url|image_url|preview_image|screenshot_url|thumb_url|preview)\s*[:=]\s*[\'"]([^\'"]+)',
             r'(?i)\b(?:player|video)[-_](?:cover|thumb|preview|poster)\b\s*[:=]\s*[\'"]([^\'"]+)'):
    for match in list(re.finditer(expr,html))[:8]:
        url=urllib.parse.urljoin(PAGE,h.unescape(match.group(1)))
        parsed=urllib.parse.urlsplit(url)
        print("OBSERVED_IMAGE_CANDIDATE",parsed.hostname,parsed.path[:160],flush=True)
