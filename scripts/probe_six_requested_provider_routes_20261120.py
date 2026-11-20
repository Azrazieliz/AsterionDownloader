#!/usr/bin/env python3
"""Read-only HTTP provider discovery: no video files, cookies or tokens saved."""
import html.parser,urllib.request,urllib.parse,urllib.error,json,collections,re
SITES={"Avple":"https://avple.tv/","PimpBunny":"https://pimpbunny.com/","xChina":"https://en.xchina.co/","Bubblecum":"https://www.bubblecum.lol/","PornAVHD":"https://pornavhd.com/","CamStreams":"https://camstreams.tv/latest-updates/"}
class Links(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.routes=[];self.posters=0;self.video=0;self.og=0
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):
   u=urllib.parse.urljoin(self.base,d["href"]);p=urllib.parse.urlsplit(u)
   origin=urllib.parse.urlsplit(self.base)
   if p.hostname and p.hostname.removeprefix("www.")==origin.hostname.removeprefix("www.") and p.path and len(p.path)<220:
    self.routes.append(p.path)
  if tag=="img":self.posters+=1
  if tag=="video":self.video+=1
  if tag=="meta" and d.get("property")=="og:image":self.og+=1
for name,url in SITES.items():
 out={"name":name,"url":url}
 try:
  req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept":"text/html,application/xhtml+xml","Accept-Language":"en-US,en;q=0.8","Accept-Encoding":"identity"})
  with urllib.request.urlopen(req,timeout=18) as resp:
   body=resp.read(750000).decode("utf-8","replace");redirect=resp.url
   out.update({"status":resp.status,"redirect_host":urllib.parse.urlsplit(redirect).hostname,"sample_bytes":len(body)})
  parser=Links(redirect);parser.feed(body)
  unique=list(dict.fromkeys(parser.routes))
  kinds=collections.Counter("/"+p.strip("/").split("/")[0] for p in parser.routes)
  out.update({"links":len(parser.routes),"unique_paths":len(unique),"images":parser.posters,"video_tags":parser.video,"og_images":parser.og,"top_sections":kinds.most_common(10),"sample_routes":unique[:20]})
 except urllib.error.HTTPError as e:out.update({"status":e.code})
 except Exception as e:out.update({"error_type":type(e).__name__,"message":str(e)[:100]})
 print(json.dumps(out,ensure_ascii=False),flush=True)
