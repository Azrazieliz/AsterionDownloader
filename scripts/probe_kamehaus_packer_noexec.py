#!/usr/bin/env python3
"""Decode only the exact selected Kamehaus JWPlayer P.A.C.K.E.R text.

Never execute third-party JavaScript. Print only media host / path category
and codec/manifest signals. Selected Roshy article attested this iframe.
"""
import re,json,urllib.parse,urllib.request
from html.parser import HTMLParser
URL="https://kamehaus.net/e/242urr647yem"
REF="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Scripts(HTMLParser):
 def __init__(self):super().__init__();self.ins=False;self.body="";self.scripts=[]
 def handle_starttag(self,tag,attrs):
  if tag=="script" and "src" not in dict(attrs):self.ins=True;self.body=""
 def handle_data(self,s):
  if self.ins and len(self.body)<300000:self.body+=s
 def handle_endtag(self,tag):
  if tag=="script" and self.ins:self.scripts.append(self.body);self.ins=False
def inspect(s):
 m=re.search(r"""(?s)eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*d\s*\).*?\}\s*\(\s*'((?:\\.|[^'\\])*)'\s*,\s*(\d{1,2})\s*,\s*(\d{1,4})\s*,\s*'((?:\\.|[^'\\])*)'\.split\('\|'\)""",s)
 if not m:return {"signature":False,"snippet_start":re.sub(r"\w{30,}","{opaque}",s[:95])}
 packed,radix,count,dictionary=m.groups();radix=int(radix);count=int(count)
 result={"signature":True,"packed_length":len(packed),"radix":radix,"count":count,
  "dictionary_words":len(dictionary.split("|"))}
 if radix<2 or radix>36 or count>1000 or len(packed)>180000:return result
 words=dictionary.split("|")
 abc="0123456789abcdefghijklmnopqrstuvwxyz"
 def base(n):
  if n==0:return "0"
  digits=[]
  while n:digits.append(abc[n%radix]);n//=radix
  return "".join(reversed(digits))
 decoded=packed
 for n in range(count-1,-1,-1):
  if n>=len(words) or not words[n]:continue
  decoded=re.sub(r"\b"+re.escape(base(n))+r"\b",lambda _:words[n],decoded)
  if len(decoded)>200000:break
 result["decoded_length"]=len(decoded)
 result["signals"]={key:len(re.findall(pattern,decoded,re.I)) for key,pattern in {
  "jwplayer":r"jwplayer","sources":r"\bsources?\s*[:=]",
  "hls":r"\.m3u8","dash":r"\.mpd","mp4":r"\.mp4",
  "file_assignments":r"\bfile\s*[:=]","atob":r"\batob\("}.items()}
 urls=re.findall(r"""https?://[^\s\"'<>\\]{12,2400}""",decoded)
 def shape(raw):
  u=urllib.parse.urlsplit(raw)
  return {"host":u.hostname,"path_prefix":u.path.split("/")[1:3],
    "ext":u.path.rsplit(".",1)[-1][:8] if "." in u.path.split("/")[-1] else "",
    "signed_query":bool(u.query)}
 result["url_shapes"]=[shape(u) for u in urls[:15]]
 result["source_patterns"]=[]
 for m in list(re.finditer(r"\b(?:file|sources|hls|playlist)\s*[:=]",decoded,re.I))[:6]:
  snippet=decoded[max(0,m.start()-35):m.end()+100]
  snippet=re.sub(r"https?://[^\s\"'<>\\]{12,2400}","[MEDIA_URL]",snippet)
  snippet=re.sub(r"[A-Za-z0-9+/=_-]{35,}","[OPAQUE]",snippet)
  result["source_patterns"].append(re.sub(r"\s+"," ",snippet)[:150])
 return result
with urllib.request.urlopen(urllib.request.Request(URL,headers={
 "User-Agent":UA,"Referer":REF,"Accept-Encoding":"identity"}),timeout=18) as r:
 body=r.read(200000).decode("utf8","replace")
p=Scripts();p.feed(body)
for index,s in enumerate(p.scripts):
 if "eval(function(p,a,c,k,e,d)" not in s:continue
 print(json.dumps({"script":index,"source_length":len(s),**inspect(s)}),flush=True)
