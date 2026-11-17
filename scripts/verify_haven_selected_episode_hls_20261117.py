#!/usr/bin/env python3
"""Selected HentaiHaven episode HLS first-party JSON-LD proof.
Find an episode URL PUBLISHED by the selected series; parse its own VideoObject
contentUrl; GET only a playlist header. No media file download or guessed URLs.
"""
import html,json,re,urllib.request,urllib.parse,urllib.error
from html.parser import HTMLParser
SERIES="https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Tags(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.scripts=[];self.active=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.links.append(a["href"])
  if tag=="script" and a.get("type","").lower()=="application/ld+json":self.active=""
 def handle_data(self,d):
  if self.active is not None:self.active+=d
 def handle_endtag(self,tag):
  if tag=="script" and self.active is not None:self.scripts.append(self.active);self.active=None
def get(url,ref,limit=850000):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=25) as r:
  return r.status,r.headers.get("Content-Type",""),r.read(limit)
def video_objects(obj):
 if isinstance(obj,list):
  for x in obj:yield from video_objects(x)
 elif isinstance(obj,dict):
  typ=obj.get("@type","")
  if "VideoObject" in ([typ] if isinstance(typ,str) else typ):yield obj
  for x in obj.values():
   if isinstance(x,(dict,list)):yield from video_objects(x)
try:
 _,_,raw=get(SERIES,SERIES)
 doc=Tags();doc.feed(raw.decode("utf8","replace"))
 prefix=urllib.parse.urlsplit(SERIES).path.rstrip("/")
 routes=sorted(set(urllib.parse.urljoin(SERIES,x) for x in doc.links
  if re.fullmatch(re.escape(prefix)+r"/episode-[0-9]+/?",
      urllib.parse.urlsplit(urllib.parse.urljoin(SERIES,x)).path)))
 assert routes,"No publisher-attested HentaiHaven episode"
 selected=routes[0]
 status,typ,raw=get(selected,SERIES)
 page=raw.decode("utf8","replace");tags=Tags();tags.feed(page)
 objects=[]
 for text in tags.scripts:
  try: objects.extend(video_objects(json.loads(html.unescape(text))))
  except Exception:continue
 candidates=[]
 for obj in objects:
  u=obj.get("contentUrl") or obj.get("embedUrl") or ""
  if not isinstance(u,str):continue
  target=urllib.parse.urlsplit(u)
  if(target.scheme=="https" and target.hostname=="octopusmanifest.org" and
     target.path.endswith(".m3u8") and not target.query and
     not target.username and target.port is None):
   candidates.append(u)
 distinct=list(dict.fromkeys(candidates))
 print(json.dumps({"stage":"SELECTED_EPISODE_VIDEOOBJECT",
   "episode_http":status,"html_bytes":len(raw),"published_episodes":len(routes),
   "jsonld_scripts":len(tags.scripts),"video_objects":len(objects),
   "unique_hls":len(distinct),
   "video_object_keys":[sorted(x)[:18] for x in objects[:2]]}),flush=True)
 if len(distinct)!=1:raise SystemExit(2)
 status,mime,body=get(distinct[0],selected,250000)
 print(json.dumps({"stage":"SELECTED_EPISODE_HLS_PLAYLIST",
  "http":status,"mime":mime.split(";")[0],
  "bytes":len(body),"extm3u":body.lstrip().startswith(b"#EXTM3U"),
  "variants":body.count(b"#EXT-X-STREAM-INF")}),flush=True)
 raise SystemExit(0 if body.lstrip().startswith(b"#EXTM3U") else 3)
except SystemExit:raise
except Exception as e:
 print(json.dumps({"stage":"EPISODE_HLS_NOT_VERIFIED","error":type(e).__name__,"detail":str(e)[:170]}),flush=True)
 raise SystemExit(4)
