#!/usr/bin/env python3
"""Read-only TaiAV public list DOM layout (no titles/media URLs/cookies)."""
import json,re,urllib.request,urllib.parse,collections
from html.parser import HTMLParser
class Cards(HTMLParser):
 def __init__(self):
  super().__init__();self.stack=[];self.cards=[];self.imgs=0
 def handle_starttag(self,t,attrs):
  a=dict(attrs);self.stack.append((t,a))
  if t=="img":self.imgs+=1
  if t=="a" and re.fullmatch(r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?",urllib.parse.urlsplit(urllib.parse.urljoin("https://taiav.com/cn/",a.get("href",""))).path,re.I):
   anc=[(x.get("class",""),tag) for tag,x in self.stack[-8:-1]]
   self.cards.append({"anc":anc,"attrs":sorted(a),"images_nested":0,"attrs_img":[]})
  if t=="img" and self.cards and self.stack:
   # The image may belong to an ancestor card, not this exact anchor.
   for card in self.cards[-3:]:
    if not card["images_nested"] and len(card["attrs_img"])<5:
     card["attrs_img"]=[k for k in a if k in ("src","srcset","data-src","data-original","data-lazy","data-lazy-src","data-image","style")]
 def handle_endtag(self,t):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==t:self.stack=self.stack[:i];break
headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"}
with urllib.request.urlopen(urllib.request.Request("https://taiav.com/cn/",headers=headers),timeout=22) as r: raw=r.read(1600000).decode("utf8","replace")
p=Cards();p.feed(raw)
c=collections.Counter()
for a in p.cards:
 c[tuple(x for x in a["anc"][-3:])]+=1
print(json.dumps({"anchors":len(p.cards),"images":p.imgs,"common_ancestor_classes":[{"ancestors":list(k),"count":n} for k,n in c.most_common(10)],"first_card_attrs":[{"anchor_attr_names":x["attrs"],"near_image_attr_names":x["attrs_img"],"ancestor_classes":x["anc"][-4:]} for x in p.cards[:5]]}),flush=True)
