#!/usr/bin/env python3
"""Selected publisher watch contract. URLs are sourced from the official
episode/nhplayer page. Emit only shape, never signed links, token, or bytes.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
SERIES="https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/"
NH="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
def get(url,ref):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity","Referer":ref})
 with urllib.request.urlopen(req,timeout=23) as r:return r.status,r.url,r.read(850000).decode("utf8","replace")
class Links(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.data_ids=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.links.append(a["href"])
  if tag=="li" and "data-id" in a:self.data_ids.append(a["data-id"])
def shape(url):
 u=urllib.parse.urlsplit(url)
 return {"host":u.hostname,"path_ext":u.path.rsplit(".",1)[-1][:12] if "." in u.path else "",
  "path_segments":len([x for x in u.path.split("/") if x]),"query":bool(u.query)}
try:
 status,_,source=get(SERIES,SERIES);parser=Links();parser.feed(source)
 base=urllib.parse.urlsplit(SERIES).path.rstrip("/")
 episodes=sorted(set(urllib.parse.urljoin(SERIES,h) for h in parser.links
  if re.fullmatch(re.escape(base)+r"/episode-[0-9]+/?",
    urllib.parse.urlsplit(urllib.parse.urljoin(SERIES,h)).path)))
 print(json.dumps({"provider":"HentaiHaven","series_http":status,
  "published_episode_count":len(episodes)}),flush=True)
 if episodes:
  status,final,raw=get(episodes[0],SERIES)
  expanded=html.unescape(raw.replace("\\/","/").replace("\\u0026","&").replace('\\"','"'))
  found={}
  for ext in ("m3u8","mp4"):
   matches=[]
   for m in re.finditer(r"https?://[^\"'<>\s\\]{6,1000}\."+ext+r"(?:\?[^\"'<>\s\\]{1,700})?",expanded,re.I):
    url=m.group(0);context=expanded[max(0,m.start()-95):m.start()]
    matches.append({"route":shape(url),"key_context":re.findall(r"[A-Za-z_]{3,35}",context)[-4:]})
    if len(matches)>=12:break
   found[ext]=matches
  print(json.dumps({"provider":"HentaiHaven","stage":"PUBLISHED_EPISODE","http":status,
   "selected_path":urllib.parse.urlsplit(final).path.replace(base,"/watch/{series}"),
   "links":found,"has_nextjs":"self.__next_f.push" in raw}),flush=True)
except Exception as e:print(json.dumps({"provider":"HentaiHaven","error":type(e).__name__,"detail":str(e)[:90]}),flush=True)
try:
 status,_,body=get(NH,NH);parser=Links();parser.feed(body)
 print(json.dumps({"provider":"NHPlayer","http":status,
  "servers":[{"len":len(v),"prefix":v[:8],"scheme":urllib.parse.urlsplit(v).scheme,
   "host":urllib.parse.urlsplit(urllib.parse.urljoin(NH,v)).hostname,
   "path":urllib.parse.urlsplit(urllib.parse.urljoin(NH,v)).path,
   "query_keys":sorted(urllib.parse.parse_qs(
     urllib.parse.urlsplit(urllib.parse.urljoin(NH,v)).query))[:12],
   "looks_like_url":bool(re.match(r"^https?://",v))} for v in parser.data_ids[:8]]}),flush=True)
except Exception as e:print(json.dumps({"provider":"NHPlayer","error":type(e).__name__}),flush=True)
