#!/usr/bin/env python3
"""Read-only publisher-selected HelloPorn card / article player provenance.

Inspect several first-party catalog cards and whether two thumbnail layouts
correspond to different publisher-declared iframe/media hosts. This does not
download a video, fabricate hoster links, or expose opaque signed URLs.
"""
import collections,concurrent.futures,html,json,re,urllib.error,urllib.parse,urllib.request
from html.parser import HTMLParser
BASE="https://helloporn.com/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
SELECTED=re.compile(r"^/video-[0-9]{1,10}-[a-z0-9][a-z0-9-]{2,240}/?$",re.I)
class Document(HTMLParser):
 def __init__(self):
  super().__init__();self.anchors=[];self.frames=[];self.video=[];self.images=[]
 def handle_starttag(self,tag,attrs):
  attrs=dict(attrs)
  if tag=="a" and attrs.get("href"):self.anchors.append(attrs)
  if tag=="iframe" and attrs.get("src"):self.frames.append(attrs)
  if tag in ("video","source"):self.video.append(attrs)
  if tag=="img":self.images.append(attrs)
def fetch(url):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=19) as res:
   return res.status,res.read(550000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:return e.code,""
 except Exception as e:return type(e).__name__,""
def locate(url,root):
 p=urllib.parse.urlsplit(urllib.parse.urljoin(root,html.unescape(url or "")))
 return p if p.scheme=="https" and p.hostname else None
status,body=fetch(BASE)
doc=Document();doc.feed(body)
pages=list(dict.fromkeys(
 p.geturl() for a in doc.anchors
 if (p:=locate(a["href"],BASE)) and
 p.hostname in ("helloporn.com","www.helloporn.com") and SELECTED.fullmatch(p.path)
))[:14]
def inspect(url):
 code,body=fetch(url);d=Document();d.feed(body)
 links=[]
 for raw in d.frames:
  p=locate(raw["src"],url)
  if p:links.append({"host":p.hostname,"route":
    "/e/" if p.path.startswith("/e/") else
    "/embed/" if p.path.startswith("/embed/") else
    "/player/" if p.path.startswith("/player/") else "other"})
 thumb_shapes=[]
 for im in d.images[:22]:
  for k in ("width","height"):
   if im.get(k):
    pass
  if any(k in im for k in ("width","height","style","class")):
   thumb_shapes.append({"width":str(im.get("width",""))[:12],
    "height":str(im.get("height",""))[:12],
    "class":str(im.get("class",""))[:80]})
 return {"http":code,
  "frames":links[:12],
  "selected_frames":len(links),
  "inline_video_tags":len(d.video),
  "thumbnail_metadata_samples":thumb_shapes[:3],
  "html_bytes":len(body)}
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
 scans=list(pool.map(inspect,pages))
print(json.dumps({"site":"helloporn.com","catalog_status":status,
 "selected_articles_discovered":len(pages),"articles":scans,
 "distinct_player_host_sets":{
   ",".join(hosts) or "(none)":amount
   for hosts,amount in collections.Counter(
     tuple(sorted(set(f["host"] for f in scan["frames"]))) for scan in scans
   ).items()},
 "sampled_original_media_bytes":0,"proof_of_selected_media":False
}),flush=True)
