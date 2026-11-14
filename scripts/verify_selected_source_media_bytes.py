#!/usr/bin/env python3
"""Live publisher selected HTML5 source verification: <=256 media bytes each.
Do not store media URLs, tokens or video content; no complete downloads.
"""
import subprocess,tempfile,re,urllib.parse,urllib.request,urllib.error,sys
from html.parser import HTMLParser
EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
SELECTED={
 "JAVIDEO":"https://javideo.net/en/fc2ppv-4994603",
 "HentaiBros":"https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
 "3DHentai":"https://3dhentaix.com/ntr-compilation/",
}
class P(HTMLParser):
 def __init__(self): super().__init__();self.stack=[];self.media=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);self.stack.append((tag,a))
  if tag in ("video","source") and (a.get("src") or a.get("data-src")):
   self.media.append((tag,a,[(t,p.get("id",""),p.get("class","")[:65]) for t,p in self.stack[-6:]]))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:self.stack=self.stack[:i];break
for name,page in SELECTED.items():
 if len(sys.argv)>1 and name not in sys.argv[1:]:continue
 with tempfile.TemporaryDirectory(prefix="asterion_live_origins_") as d:
  try:
   p=subprocess.run([EDGE,"--headless=new","--disable-gpu","--no-first-run",
      "--disable-extensions","--mute-audio","--user-data-dir="+d,
      "--virtual-time-budget=7000","--dump-dom",page],capture_output=True,timeout=26)
   text=p.stdout.decode("utf-8","replace")
   parser=P();parser.feed(text)
   print(name,"DOM",p.returncode,"elements",len(parser.media),flush=True)
   for tag,attrs,ancestors in parser.media[:4]:
    link=urllib.parse.urljoin(page,attrs.get("src") or attrs.get("data-src") or "")
    u=urllib.parse.urlsplit(link)
    print(" media",tag,"host",u.hostname,"path_ext",u.path.split(".")[-1][:8],
      "class",attrs.get("class","")[:70],"parents",ancestors[-4:],flush=True)
    if u.scheme!="https" or not u.hostname or not u.path.lower().endswith((".mp4",".m4v",".webm")):continue
    request=urllib.request.Request(link,headers={
      "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
      "Referer":page,"Range":"bytes=0-255","Accept-Encoding":"identity"})
    try:
     with urllib.request.urlopen(request,timeout=12) as r:
      chunk=r.read(256)
      print(" original_range",r.status,r.headers.get("Content-Type","").split(";")[0],
          r.headers.get("Content-Range","")[:70],
          "ftyp",chunk[4:8]==b"ftyp","read",len(chunk),flush=True)
    except urllib.error.HTTPError as e:print(" range_http",e.code,flush=True)
    except Exception as e:print(" range_error",type(e).__name__,flush=True)
  except Exception as e:print(name,"probe_error",type(e).__name__,flush=True)
