#!/usr/bin/env python3
"""Selected Roshy -> Kamehaus packed JWPlayer -> legitimate HLS playlist probe.

Uses exact publisher-selected embed. Unpacks P.A.C.K.E.R as TEXT ONLY.
Accepts only literal HLS URLs in the selected player's links object and
verifies actual #EXTM3U bytes. Does not run third-party JS or bypass gates.
Never prints signed manifest or credentials.
"""
import json,re,urllib.parse,urllib.request,sys
from html.parser import HTMLParser
URL="https://kamehaus.net/e/242urr647yem"
PARENT="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Script(HTMLParser):
 def __init__(self):super().__init__();self.ins=False;self.tmp="";self.raw=[]
 def handle_starttag(self,t,a):
  if t=="script" and not dict(a).get("src"):self.ins=True;self.tmp=""
 def handle_data(self,s):
  if self.ins and len(self.tmp)<150000:self.tmp+=s
 def handle_endtag(self,t):
  if t=="script" and self.ins:self.raw.append(self.tmp);self.ins=False
def request(url,referer):
 return urllib.request.urlopen(urllib.request.Request(url,headers={
  "User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity",
  "Accept":"application/vnd.apple.mpegurl,application/x-mpegURL,*/*"}),timeout=25)
def deobfuscate(script):
 pattern=r"""(?s)eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\s*\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d{1,2})\s*,\s*(\d{1,4})\s*,\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)"""
 m=re.search(pattern,script)
 if not m:return ""
 packed,radix,count,words=m.groups();radix=int(radix);count=int(count)
 if radix not in range(2,37) or count not in range(1,851) or len(packed)>120000:return ""
 names=words.split("|")
 chars="0123456789abcdefghijklmnopqrstuvwxyz"
 def enc(n):
  if n==0:return "0"
  s=""
  while n:s=chars[n%radix]+s;n//=radix
  return s
 for i in range(count-1,-1,-1):
  if i>=len(names) or not names[i]:continue
  packed=re.sub(r"\b"+re.escape(enc(i))+r"\b",lambda _:names[i],packed)
  if len(packed)>180000:return ""
 return packed
def main():
 with request(URL,PARENT) as r:
  assert r.status==200
  page=r.read(200000).decode("utf8","replace")
 scan=Script();scan.feed(page)
 decoded=next((deobfuscate(s) for s in scan.raw if
   "eval(function(p,a,c,k,e,d)" in s),"")
 assert decoded and "jwplayer" in decoded, "No exact public packed JWPlayer config"
 # The selected player declares links.hls4||links.hls3||links.hls2,
 # not a direct <video src>. Never scan arbitrary URLs outside that config.
 for m in list(re.finditer(r"\blinks\s*=",decoded))[:7]:
  snippet=decoded[max(0,m.start()-35):m.start()+330]
  snippet=re.sub(r"https?://[^\s\"'<>]+",lambda k:
   "[HOST:"+str(urllib.parse.urlsplit(k.group(0)).hostname)+"]",snippet)
  print("LINKS_CONTEXT",re.sub(r"\s+"," ",snippet)[:320],flush=True)
 patterns=[
  r"""\bhls2\s*:\s*['"](https://[^'"]{25,2000})['"]""",
  r"""\bhls3\s*:\s*['"](https://[^'"]{25,2000})['"]""",
  r"""\bhls4\s*:\s*['"](https://[^'"]{25,2000})['"]"""]
 media=[]
 for typ,pat in zip(("hls2","hls3","hls4"),patterns):
  results=list(re.finditer(pat,decoded,re.I))
  if len(results)!=1:continue
  address=results[0].group(1).replace("\\/","/")
  u=urllib.parse.urlsplit(address)
  if u.scheme!="https" or not u.hostname or u.username or u.password:continue
  print(json.dumps({"published_hls":typ,"host":u.hostname,
    "path_ext":u.path.rsplit(".",1)[-1][:8],
    "signed":bool(u.query)}),flush=True)
  media.append((typ,address))
 if not media:return 2
 for typ,address in media:
  try:
   with request(address,URL) as response:
    buf=response.read(200000)
    print(json.dumps({"kind":typ,"http":response.status,
      "mime":response.headers.get("Content-Type","").split(";")[0],
      "bytes":len(buf),"valid_extm3u":buf.lstrip().startswith(b"#EXTM3U"),
      "playlist_lines":len(buf.splitlines()),
      "host":urllib.parse.urlsplit(address).hostname}),flush=True)
    if response.status==200 and buf.lstrip().startswith(b"#EXTM3U"):
     print(json.dumps({"provider":"roshy",
       "status":"SELECTED_ORIGINAL_HLS_PLAYLIST_VERIFIED_NOT_FULL_FILE",
       "mode":typ}),flush=True)
     return 0
  except Exception as err:
   print("HLS_REQUEST_FAILED",typ,type(err).__name__,
         str(err)[:100],flush=True)
 return 3
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"status":"FAILED_NOT_COMPLETE","error":type(e).__name__,
    "reason":str(e)[:180]}),flush=True);sys.exit(4)
