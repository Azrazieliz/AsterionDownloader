#!/usr/bin/env python3
"""Exact NHPlayer-selected first player server is first-party /player.php.
Read only the public wrapper and report whether it publishes a media URL
or exact nested embed; never invent media, solve CAPTCHA, or save a video.
"""
import json,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
NH="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class P(HTMLParser):
 def __init__(self):super().__init__();self.servers=[];self.frames=[];self.videos=[];self.sources=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="li" and "data-id" in a:self.servers.append(a["data-id"])
  if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
  if tag=="video" and a.get("src"):self.videos.append(a["src"])
  if tag=="source" and a.get("src"):self.sources.append(a["src"])
def get(url,ref):
 req=urllib.request.Request(url,headers={
  "User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=23) as r:return r.status,r.url,r.headers.get("Content-Type",""),r.read(500000)
def shape(u,base):
 p=urllib.parse.urlsplit(urllib.parse.urljoin(base,u))
 return {"host":p.hostname,"path":"/"+"/".join(x if x in ("e","v","embed","player.php","video","watch")
  else "{id}" for x in p.path.split("/") if x),"has_query":bool(p.query)}
try:
 status,_,_,body=get(NH,NH);p=P();p.feed(body.decode("utf8","replace"))
 first=urllib.parse.urljoin(NH,p.servers[0])
 u=urllib.parse.urlsplit(first)
 assert u.scheme=="https" and u.hostname=="nhplayer.com" and u.path=="/player.php" and u.username is None
 assert set(urllib.parse.parse_qs(u.query))=={"i","s","vid"}
 status,land,mime,body=get(first,NH)
 q=P();q.feed(body.decode("utf8","replace"))
 print(json.dumps({"stage":"PUBLISHED_NHPLAYER_FIRST_SERVER",
  "host":urllib.parse.urlsplit(land).hostname,
  "http":status,"mime":mime.split(";")[0],"html_bytes":len(body),
  "iframe_sources":[shape(x,land) for x in q.frames[:6]],
  "html_video_sources":[shape(x,land) for x in q.videos[:5]],
  "html_source_tags":[shape(x,land) for x in q.sources[:6]],
  "m3u8_lit":body.lower().count(b".m3u8"),"mp4_lit":body.lower().count(b".mp4"),
  "js_script_markers":{k:body.lower().count(k.encode("ascii")) for k in
   ("jwplayer","videojs","iframe","htstreaming","blob:","atob(")} }),flush=True)
except Exception as e:
 print(json.dumps({"stage":"NHPLAYER_NOT_VERIFIED","error":type(e).__name__,
  "reason":str(e)[:170]}),flush=True)
