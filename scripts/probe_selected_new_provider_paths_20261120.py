#!/usr/bin/env python3
import urllib.request,urllib.parse,urllib.error,json,re,html.parser
SITES=[
("PimpBunny","https://pimpbunny.com/",r"^/(?:videos?|onlyfans-creators)/"),
("xChina","https://en.xchina.co/",r"^/(?:video|models?)/"),
("Bubblecum","https://www.bubblecum.lol/",r"^/(?:video|models?)/"),
("PornAVHD","https://pornavhd.com/",r"^/20[0-9]{2}/"),
("CamStreams","https://camstreams.tv/latest-updates/",r"^/(?:videos|models)/")]
class P(html.parser.HTMLParser):
 def __init__(self):super().__init__();self.paths=[]
 def handle_starttag(self,tag,attrs):
  if tag!="a":return
  d=dict(attrs);u=d.get("href","")
  if u:self.paths.append(u)
for name,site,pattern in SITES:
 try:
  req=urllib.request.Request(site,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity"})
  with urllib.request.urlopen(req,timeout=18) as r:
   raw=r.read(720000).decode("utf-8","replace");base=r.url
  p=P();p.feed(raw)
  routes=list(dict.fromkeys(urllib.parse.urlsplit(urllib.parse.urljoin(base,u)).path for u in p.paths))
  filtered=[u for u in routes if re.search(pattern,u,re.I)]
  print(json.dumps({"provider":name,"example_routes":filtered[:9],"count":len(filtered)}),flush=True)
 except Exception as e:print(json.dumps({"provider":name,"error":type(e).__name__}),flush=True)
