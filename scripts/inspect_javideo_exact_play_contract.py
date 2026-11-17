#!/usr/bin/env python3
"""Inspect exact JAVIDEO selected article's FIRST-PARTY playback controls.

Does not execute publisher JavaScript, enumerate related cards, follow ads,
or claim that an iframe/preview is the selected original. Print safe player
DOM/JS structure, no signed external URLs/cookies.
"""
import re,json,urllib.request,urllib.parse,html
from html.parser import HTMLParser
PAGE="https://javideo.net/en/fc2ppv-4994603"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Parser(HTMLParser):
 def __init__(self):
  super().__init__();self.stack=[];self.buttons=[];self.frames=[];self.scripts=[];self.current=None
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="script" and not a.get("src"):self.current=""
  elif t=="script" and a.get("src"):self.scripts.append(("external",a.get("src")))
  if t in ("button","iframe","video","source","a","div"):
   scope=" ".join(" ".join([x.get("id",""),x.get("class","")]) for x in self.stack[-5:])
   if re.search(r"play|player|embed|video|stream|iframe",scope+" "+a.get("class","")+" "+a.get("id",""),re.I):
    obj={"tag":t,"class":a.get("class","")[:90],"id":a.get("id","")[:55],
      "data_keys":[k for k in a if k.startswith("data-")][:12],
      "onclick":re.sub(r"https?://[^\s'\"<>]+","{url}",a.get("onclick","")[:120])}
    for k in ("src","data-src","href"):
     u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,html.unescape(a.get(k,""))))
     if u.scheme in ("http","https") and u.hostname:obj[k+"_host"]=u.hostname
    if t=="iframe":self.frames.append(obj)
    elif len(self.buttons)<80:self.buttons.append(obj)
  if t not in ("img","br","meta","source","input","link","hr","wbr","area"):
   self.stack.append({"tag":t,"id":a.get("id",""),"class":a.get("class","")})
 def handle_data(self,s):
  if self.current is not None and len(self.current)<100000:self.current+=s
 def handle_endtag(self,t):
  if t=="script" and self.current is not None:self.scripts.append(("inline",self.current));self.current=None
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i]["tag"]==t:del self.stack[i:];break
with urllib.request.urlopen(urllib.request.Request(PAGE,headers={"User-Agent":UA,
 "Accept-Encoding":"identity"}),timeout=20) as r:
 code=r.status;page=r.read(1_600_000).decode("utf8","replace")
p=Parser();p.feed(page)
print(json.dumps({"http":code,"bytes":len(page),
 "relevant_elements":p.buttons[:35],"frames":p.frames[:10],
 "streamtape_occurrences":len(re.findall("streamtape|sbtape",page,re.I)),
 "captcha_occurrences":len(re.findall("turnstile|captcha",page,re.I)),
 "scripts":len(p.scripts)}),flush=True)
for typ,script in p.scripts[:38]:
 if typ!="inline":continue
 if not re.search(r"streamtape|embed|player|iframe|video",script,re.I):continue
 notes=[]
 for m in list(re.finditer(r"streamtape|sbtape|iframe|video|embed|player|play\(|\.click",script,re.I))[:7]:
  chunk=script[max(0,m.start()-70):m.end()+85]
  chunk=re.sub(r"https?://[^\s'\"<>]+","{url}",chunk)
  chunk=re.sub(r"[a-zA-Z0-9_=-]{48,}","{opaque}",chunk)
  notes.append(re.sub(r"\s+"," ",chunk)[:160])
 print(json.dumps({"script":typ,"chars":len(script),"notes":notes}),flush=True)
