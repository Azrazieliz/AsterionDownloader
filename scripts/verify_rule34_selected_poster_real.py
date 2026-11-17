#!/usr/bin/env python3
"""Publisher-authenticated selected Rule34 video page poster probe.

Inspect only actual selected OG/twitter/video-poster metadata. Never invent
a thumbnail, substitute homepage images, or download full media.
"""
import re,json,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
PAGE="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Selected(HTMLParser):
 def __init__(self):super().__init__();self.tags=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="meta" and d.get("property","").lower() in (
   "og:image","og:image:url") or tag=="meta" and d.get("name","").lower()=="twitter:image":
   self.tags.append((tag,"metadata",d.get("content","")))
  elif tag=="video" and d.get("poster"):self.tags.append((tag,"poster",d["poster"]))
  elif tag=="link" and d.get("rel","")=="image_src":self.tags.append((tag,"image_src",d.get("href","")))
def get(url,ref=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if ref:h["Referer"]=ref
 return urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=18)
with get(PAGE) as r:
 body=r.read(1000000).decode("utf-8","replace")
 print("WATCH_HTTP",r.status,"HTML_BYTES",len(body),flush=True)
parsed=Selected();parsed.feed(body)
seen=set()
for tag,kind,raw in parsed.tags[:16]:
 url=urllib.parse.urljoin(PAGE,raw.strip())
 uri=urllib.parse.urlsplit(url)
 if uri.scheme!="https" or not uri.hostname or url in seen:continue
 seen.add(url)
 if any(t in uri.path.lower() for t in ("logo","avatar","ads","placeholder","banner","sprite")):continue
 try:
  with get(url,PAGE) as r:
   chunk=r.read(4096)
   mime=r.headers.get("Content-Type","").split(";")[0]
   magic=(chunk[:2]==b"\xff\xd8" or chunk[:4]==b"\x89PNG" or
    (chunk[:4]==b"RIFF" and chunk[8:12]==b"WEBP"))
   print(json.dumps({"kind":kind,"host":uri.hostname,
    "status":r.status,"mime":mime,"bytes_sample":len(chunk),
    "image_magic":magic,"length_header":r.headers.get("Content-Length"),
    "url_path":uri.path[:85]}),flush=True)
 except Exception as e:print("POSTER_ERROR",kind,type(e).__name__,str(e)[:110],flush=True)
print("PUBLISHED_POSTER_CANDIDATES",len(seen),flush=True)
