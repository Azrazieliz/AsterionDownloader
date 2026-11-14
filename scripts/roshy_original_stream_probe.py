#!/usr/bin/env python3
"""Probe selected Roshy published 'Original Stream' player, not article links.
Redact signed URLs, cookies and media tokens. No media bytes transferred.
"""
import re,urllib.request,urllib.parse,html
from html.parser import HTMLParser
PAGE="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
class Elements(HTMLParser):
 def __init__(self):super().__init__();self.players=[];self.links=[];self.inscript=False;self.script=""
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag in ("video","source","iframe","track"):self.players.append((tag,a))
  if tag=="a":self.links.append(a)
  if tag=="script" and not a.get("src"):self.inscript=True;self.script=""
 def handle_data(self,t):
  if self.inscript:self.script+=t[:50000]
 def handle_endtag(self,tag):
  if tag=="script":self.inscript=False
def shape(raw):
 try:
  if raw.startswith("data:") or raw.startswith("blob:"):return raw.split(":")[0]+":"
  u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,raw))
  return (u.hostname or "")+re.sub(r"/[A-Za-z0-9_-]{22,}","/{id}",u.path)[:120]
 except:return "?"
def attrs(a):
 keys=("id","class","type","preload","controls","poster","src","data-src","data-file","data-url","data-video","data-video-src","data-quality","data-stream","onclick","href","title")
 return {key:(shape(value) if key in ("src","data-src","data-file","data-url","data-video","data-video-src","data-stream","href") else value[:80])
     for key,value in a.items() if key in keys}
req=urllib.request.Request(PAGE,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"})
with urllib.request.urlopen(req,timeout=17) as r:
 raw=r.read(1500000).decode("utf8","replace")
print("page_chars",len(raw))
p=Elements();p.feed(raw)
print("video+source_count",len(p.players))
for tag,a in p.players[:8]:print(tag,attrs(a))
print("links_with_original_download")
for a in p.links:
 if any("roshy-mirror-switch" in str(v).lower() for v in a.values()):
  print("mirror",attrs(a))
print("near_original_stream")
for q in ('id="roshy-video-mirror"','class="classic-pos-video-player','Original Stream','data-src=','videoUrl','jwplayer','streaming'):
 for m in list(re.finditer(re.escape(q),raw,re.I))[:1]:
  snippet=raw[max(0,m.start()-120):min(len(raw),m.end()+250)]
  snippet=re.sub(r"https?://[^\s'\"<>]+",lambda z:"[HOST:"+str(urllib.parse.urlsplit(z.group()).hostname)+"]",snippet)
  snippet=re.sub(r"[A-Za-z0-9_-]{45,}","[ID]",snippet)
  print(q,re.sub(r"\s+"," ",snippet)[:280])
