#!/usr/bin/env python3
"""Inspect only exact Roshy-published Kamehaus selected player scripts.
No media URLs or cookies printed, no general JS execution or bypass.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
PARENT="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
URL="https://kamehaus.net/e/242urr647yem"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Tags(HTMLParser):
 def __init__(self):
  super().__init__();self.scripts=[];self.current=None;self.inline=[];self.frames=[];self.media=[];self.inputs=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="script":
   if d.get("src"):self.scripts.append(d["src"])
   else:self.current=""
  elif tag in ("iframe","video","source"):getattr(self,"frames" if tag=="iframe" else "media").append((tag,d))
  elif tag in ("button","input"):self.inputs.append(d)
 def handle_data(self,data):
  if self.current is not None and len(self.current)<300000:self.current+=data
 def handle_endtag(self,tag):
  if tag=="script" and self.current is not None:self.inline.append(self.current);self.current=None
def urlshape(raw):
 u=urllib.parse.urlsplit(urllib.parse.urljoin(URL,html.unescape(raw)))
 return {"host":u.hostname,"route":re.sub(r"[A-Za-z0-9_-]{20,}","{id}",u.path)[:100]}
def safe_excerpt(raw):
 s=re.sub(r"https?://[^\s'\"<>]+","[publisher_url]",raw)
 s=re.sub(r"[A-Za-z0-9+/=-]{60,}","[opaque]",s)
 return re.sub(r"\s+"," ",s).strip()[:145]
req=urllib.request.Request(URL,headers={"User-Agent":UA,"Referer":PARENT,
  "Accept-Encoding":"identity"})
with urllib.request.urlopen(req,timeout=20) as r:
 body=r.read(1600000).decode("utf8","replace")
 print("PAGE_STATUS",r.status,"HTML_BYTES",len(body),flush=True)
p=Tags();p.feed(body)
print("SCRIPTS",json.dumps([urlshape(x) for x in p.scripts[:22]]),flush=True)
print("FRAMES",json.dumps([{"tag":t,**urlshape(a.get("src",""))}
 for t,a in p.frames]),flush=True)
print("MEDIA",json.dumps([{"tag":t,**urlshape(a.get("src",""))}
 for t,a in p.media]),flush=True)
print("INPUT_IDS",json.dumps([{"id":x.get("id",""),"class":x.get("class","")}
 for x in p.inputs[:12]]),flush=True)
for n,s in enumerate(p.inline[:25]):
 hints={label:len(re.findall(expr,s,re.I)) for label,expr in {
   "packed":r"eval\(function|function\(p,a,c,k,e,d",
   "atob":r"atob\(|base64","fetch":r"fetch\(|XMLHttpRequest|\.ajax",
   "hls":r"m3u8","dash":r"\.mpd","mp4":r"\.mp4",
   "file":r"\bfile\s*[:=]","src":r"\bsrc\s*[:=]",
   "redirect":r"window\.location|location\.href",
   "script_create":r"createElement\(['\"]script",
   "player":r"jwplayer|videojs|plyr|player","challenge":r"captcha|turnstile|challenge"
 }.items() if re.search(expr,s,re.I)}
 if hints:
  excerpts=[]
  for m in list(re.finditer("fetch|atob|jwplayer|source|video|stream|file|url",s,re.I))[:4]:
   excerpts.append(safe_excerpt(s[max(0,m.start()-50):m.end()+70]))
  print("INLINE",n,"size",len(s),"markers",hints,"excerpts",excerpts,flush=True)
