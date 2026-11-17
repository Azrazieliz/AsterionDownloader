#!/usr/bin/env python3
"""Public selected HentaiHaven/NHPlayer page player JS shape, no media URLs
or opaque tokens printed. No login, access bypass, automated click or file save.
"""
import re,json,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
PAGES={
"hentaihaven":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
"nhplayer":"https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
}
class Page(HTMLParser):
 def __init__(self):super().__init__();self.script=None;self.inline=[];self.external=[];self.nodes=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="script":
   if a.get("src"):self.external.append(a["src"])
   else:self.script=""
  if tag in ("div","section","iframe","video","source","button"):
   if re.search("player|video|watch|embed|stream|play",a.get("id","")+" "+a.get("class",""),re.I):
    self.nodes.append({"tag":tag,"id":a.get("id","")[:45],
      "classes":a.get("class","")[:100],
      "data_keys":sorted(k for k in a if k.startswith("data-"))[:14],
      "has_src":bool(a.get("src"))})
 def handle_data(self,data):
  if self.script is not None and len(self.script)<100000:self.script+=data
 def handle_endtag(self,tag):
  if tag=="script" and self.script is not None:
   self.inline.append(self.script);self.script=None
def req(url,ref):
 headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"}
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=20) as r:
   return r.status,r.read(700000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:
  return e.code,e.read(100000).decode("utf-8","replace")
def sanitize(text):
 text=re.sub(r"https?://[^\\s\\\"'<>]+","[url]",text)
 text=re.sub(r"[A-Za-z0-9_-]{24,}","[opaque]",text)
 return text[:480]
KEYS={"m3u8":r"\.m3u8","mp4":r"\.mp4","iframe":r"iframe",
 "videojs":r"videojs|video-js","player":r"player","source":r"source",
 "ajax":r"fetch\s*\(|XMLHttpRequest|ajax\s*\(",
 "embed":r"embed","json":r"application/json|JSON\.parse",
 "wp":r"wp-embed|wp-block","atob":r"atob\("}
for name,url in PAGES.items():
 try:
  status,body=req(url,url);doc=Page();doc.feed(body)
  scripts=[]
  for inline in doc.inline[:75]:
   markers={key:len(re.findall(expr,inline,re.I)) for key,expr in KEYS.items()}
   if sum(markers.values())>2:
    samples=[]
    for needle in ("iframe","videojs","jwplayer","sources","player","m3u8","mp4"):
     p=inline.lower().find(needle)
     if p>=0:
      samples.append(sanitize(inline[max(0,p-100):p+310]))
     if len(samples)>=2:break
    scripts.append({"length":len(inline),"markers":markers,"samples":samples})
  print(json.dumps({"provider":name,"http":status,
    "page_markers":{k:len(re.findall(v,body,re.I)) for k,v in KEYS.items()},
    "player_nodes":doc.nodes[:16],"script_contracts":scripts[:9],
    "first_party_scripts":[x for x in doc.external
      if urllib.parse.urlsplit(urllib.parse.urljoin(url,x)).hostname==
        urllib.parse.urlsplit(url).hostname][:10]}),flush=True)
 except Exception as e:print(json.dumps({"provider":name,"error":type(e).__name__}),flush=True)
