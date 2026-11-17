#!/usr/bin/env python3
"""Redacted decoded expression of one real publisher-attested Mixdrop player.

Only an actual iframe from selected Archivebate watch and same-ID canonical
redirect, never ads. Raw signed media URLs, query and long opaque IDs redacted.
"""
import json,re,urllib.parse,urllib.request,sys
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_roshy_selected_packed_hls import Script,deobfuscate,UA
WATCH="https://archivebate.com/watch/16458050"
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="iframe" and a.get("src"):self.links.append(a["src"])
def get(u,referer):
 with urllib.request.urlopen(urllib.request.Request(u,headers={
  "User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}),timeout=23) as r:
  return r.url,r.read(500000).decode("utf8","replace")
_,markup=get(WATCH,WATCH)
f=Frames();f.feed(markup)
frames=[urllib.parse.urljoin(WATCH,x) for x in f.links if
 urllib.parse.urlsplit(urllib.parse.urljoin(WATCH,x)).hostname=="mixdrop.ag"]
assert len(set(frames))==1
link=frames[0]
final,body=get(link,WATCH)
assert urllib.parse.urlsplit(link).path==urllib.parse.urlsplit(final).path
s=Script();s.feed(body)
parts=[deobfuscate(x) for x in s.raw if "eval(function(p,a,c,k,e,d)" in x]
assert len(parts)==1 and len(parts[0]) in range(100,5000)
script=parts[0]
def replace(m):
 value=m.group(0)
 uri=urllib.parse.urlsplit(value if value.startswith("http") else "https:"+value)
 return "[URL host="+str(uri.hostname)+" path-ext="+uri.path.rsplit(".",1)[-1][-7:]+"]"
script=re.sub(r"(?:https?:)?//[A-Za-z0-9.-]+/[^\s'\"<>]{1,600}",replace,script)
script=re.sub(r"(?<![a-zA-Z0-9])([a-zA-Z0-9_-]{24,})(?![a-zA-Z0-9])","[opaque]",script)
print(json.dumps({"selected":True,"js":re.sub(r"\s+"," ",script)[:2200]}))
