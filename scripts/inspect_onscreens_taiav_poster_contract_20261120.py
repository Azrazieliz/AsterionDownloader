#!/usr/bin/env python3
"""Read-only actual OnScreens/TaiAV public listing and selected article poster
contracts. Does not download media, enumerate private users, or print URLs,
titles, cookies, or tokens. Analyze the exact first-party HTML only.
"""
from html.parser import HTMLParser
from urllib.parse import urljoin,urlsplit
import urllib.request,urllib.error,json,re,collections

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

class Structure(HTMLParser):
 def __init__(self):
  super().__init__();self.images=[];self.anchors=[];self.meta=[];self.frames=[]
  self.inside=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):
   self.inside={"href":a["href"],"images":[]};self.anchors.append(self.inside)
  elif tag=="img":
   keys={k:a[k] for k in ("src","data-src","data-original","data-lazy-src",
            "data-thumb","data-preview","srcset") if a.get(k)}
   self.images.append(keys)
   if self.inside is not None:self.inside["images"].append(keys)
  elif tag=="meta" and a.get("property") in ("og:image","og:image:secure_url","og:video"):
   self.meta.append((a["property"],a.get("content","")))
  elif tag=="iframe":
   self.frames.append(a.get("src",""))
 def handle_endtag(self,tag):
  if tag=="a":self.inside=None

def fetch(url):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=24) as r:
   return r.status,r.url,r.read(1500000).decode("utf8","replace")
 except urllib.error.HTTPError as e:return e.code,url,""
 except Exception as e:return type(e).__name__,url,""

def summary(name,url):
 code,final,raw=fetch(url);doc=Structure();doc.feed(raw)
 selected=[]
 for anchor in doc.anchors:
  full=urljoin(final,anchor["href"]);p=urlsplit(full)
  matches=(name=="onscreens" and
   re.fullmatch(r"/[a-f0-9-]{36}/[a-z0-9-]{9,255}/?",p.path,re.I)) or (
    name=="taiav" and re.fullmatch(r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?",p.path,re.I))
  if matches and p.hostname and p.hostname.removeprefix("www.")==urlsplit(url).hostname.removeprefix("www."):
   selected.append(anchor)
 covers=[]
 for a in selected:
  for im in a["images"]:
   for key in ("data-original","data-src","data-lazy-src","data-thumb","data-preview","src"):
    if im.get(key):covers.append((a["href"],urljoin(final,im[key])));break
 counts=collections.Counter(source for _,source in covers)
 out={"provider":name,"listing_http":code,"listing_html_chars":len(raw),
      "all_images":len(doc.images),"selected_articles":len(selected),
      "selected_cards_with_inline_image":len(covers),
      "unique_inline_image_sources":len(counts),
      "repeated_cover_sources":sum(c>1 for c in counts.values()),
      "maximum_cover_repetition":max(counts.values(),default=0)}
 if selected:
  selected_url=urljoin(final,selected[0]["href"])
  subcode,subfinal,sub=fetch(selected_url);picked=Structure();picked.feed(sub)
  out.update({"selected_http":subcode,"selected_has_og_image":any(x[0].startswith("og:image") for x in picked.meta),
      "selected_og_image_count":sum(x[0].startswith("og:image") for x in picked.meta),
      "selected_iframe_hosts":sorted({urlsplit(urljoin(subfinal,v)).hostname for v in picked.frames})[:6]})
 return out

if __name__=="__main__":
 for name,url in [("onscreens","https://www.onscreens.me/"),
                   ("taiav","https://taiav.com/cn/")]:
  try:print(json.dumps(summary(name,url)),flush=True)
  except Exception as e:print(json.dumps({"provider":name,"error_type":type(e).__name__}),flush=True)
