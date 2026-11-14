#!/usr/bin/env python3
"""Inspect ONLY four selected publisher player HTML documents, not video bytes."""
import re,urllib.request,urllib.parse,html
from html.parser import HTMLParser
CASES={
 "fullporner":"https://fullporner.com/player/9509933/14",
 "hqporner":"https://myxstudio.top/embed/5d8297e451a0e650ca",
 "nhplayer":"https://nhplayer.com/v/Z2flAXXPucyCbVJ/",
 "javideo":"https://javideo.net/en/fc2ppv-4994603",
}
class T(HTMLParser):
 def __init__(self):
  super().__init__();self.tags=[];self.script=False;self.inline=[];self.buf=""
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag in ("iframe","video","source","script"):self.tags.append((tag,d))
  if tag=="script" and "src" not in d:self.script=True;self.buf=""
 def handle_data(self,data):
  if self.script:self.buf+=data[:100000]
 def handle_endtag(self,tag):
  if tag=="script" and self.script:self.inline.append(self.buf);self.buf="";self.script=False
def urlshape(base,raw):
 if not raw:return ""
 u=urllib.parse.urlsplit(urllib.parse.urljoin(base,html.unescape(raw)))
 return (u.hostname or "")+" "+re.sub(r"[A-Za-z0-9_-]{18,}","{id}",
          re.sub(r"[0-9]{5,}","{n}",u.path))[:90]
def inspect(k,url):
 req=urllib.request.Request(url,headers={
   "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
   "Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=12) as r:
   status=r.status;body=r.read(1800000).decode("utf8","replace")
 except Exception as e:
  print(k,type(e).__name__,str(e)[:60],flush=True);return
 parsed=T();parsed.feed(body)
 print(k,"HTTP",status,"LEN",len(body),flush=True)
 for tag,a in parsed.tags[:20]:
  attrs={key:(urlshape(url,val) if key in ("src","href","data-src","data-file") else val[:45])
         for key,val in a.items()
         if key in ("src","href","data-src","data-file","id","class","type","allow")}
  print("  TAG",tag,attrs,flush=True)
 for script in parsed.inline[:8]:
  # Only inspect player-related code shape. Print no actual URL/tokens.
  for match in list(re.finditer(r"(?i)fluidPlayer|document\.getElementById|flvv|atob\(|btq\(|(?:file|src|source|url|video)\s*[:=]|\.mp4|\.m3u8|iframe",script))[:9]:
   snippet=script[max(0,match.start()-55):min(len(script),match.end()+90)]
   snippet=re.sub(r"https?://[^\s'\"<>]+","[URL]",snippet)
   snippet=re.sub(r"[A-Za-z0-9+/_=-]{30,}","[TOKEN]",snippet)
   print("  JS",re.sub(r"\s+"," ",snippet)[:150],flush=True)
for k,u in CASES.items():inspect(k,u)
