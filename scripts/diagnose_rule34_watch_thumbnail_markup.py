#!/usr/bin/env python3
"""Read-only selected Rule34Video page thumbnail markup diagnosis.

No substitute URLs are invented. Print only publisher-observed image
attributes and DOM context, no media requests.
"""
import json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
PAGE="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Tags(HTMLParser):
 def __init__(self):super().__init__();self.stack=[];self.images=[];self.meta=[];self.styles=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  cls=d.get("class","")[:110]
  if tag in ("img","video","source","picture","meta","link"):
   ref={}
   for k in ("src","srcset","poster","data-src","data-poster","data-thumb",
             "data-preview","data-original","data-thumb-url","data-thumbnail-url",
             "content","property","name","href","style","id","class"):
    v=d.get(k,"")
    if v:
     if k in ("src","poster","data-src","data-poster","content","href"):
      u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,v))
      if u.scheme in ("http","https") and u.hostname:
       v=u.hostname+" "+u.path[:120]
     ref[k]=v[:180]
   if tag in ("img","video","source","picture"):
    if len(self.images)<45:self.images.append({"tag":tag,"in":self.stack[-4:],"attrs":ref})
   else:
    if len(self.meta)<100:self.meta.append({"tag":tag,"attrs":ref})
  if tag not in ("img","br","input","hr","meta","link","source","wbr","area"):
   self.stack.append((tag,d.get("id","")[:40],cls[:60]))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
with urllib.request.urlopen(urllib.request.Request(PAGE,headers={
 "User-Agent":UA,"Accept-Encoding":"identity"}),timeout=22) as resp:
 body=resp.read(1800000).decode("utf-8","replace")
 print("HTTP",resp.status,"BYTES",len(body),flush=True)
t=Tags();t.feed(body)
for key in ["poster","thumb","screenshot","preview","player","jpg","webp",
            "og:image","twitter:image"]:
 print("MARKER",key,len(re.findall(re.escape(key),body,re.I)),flush=True)
print("METADATA",json.dumps(t.meta[:40],ensure_ascii=False),flush=True)
print("MEDIA",json.dumps(t.images[:35],ensure_ascii=False),flush=True)
