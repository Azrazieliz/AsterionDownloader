#!/usr/bin/env python3
"""Redacted exact selected Pornhub flashvars JSON/mediaDefinitions contract.

Only the publisher's first viewkey watch linked from its own home.
No signed URLs/video titles/credentials shown; no DRM or access bypass.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
URL="https://www.pornhub.com/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Links(HTMLParser):
 def __init__(self):super().__init__();self.hrefs=[]
 def handle_starttag(self,t,a):
  d=dict(a)
  if t=="a" and d.get("href"):self.hrefs.append(d["href"])
def get(url,ref=None):
 headers={"User-Agent":UA,"Accept-Encoding":"identity"}
 if ref:headers["Referer"]=ref
 with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=22) as r:
  return r.read(1700000).decode("utf8","replace")
def red(text):
 text=re.sub(r"https?(?::|\\u003a|\\\/|/){1,5}[^\"'\s<>]{6,1000}",
  lambda m:"[URL_HOST_"+str(urllib.parse.urlsplit(m.group().replace("\\/","/")).hostname)+"]",text,flags=re.I)
 text=re.sub(r"(?<![\w])[A-Za-z0-9_-]{28,}(?![\w])","[opaque]",text)
 return re.sub(r"\s+"," ",text)[:470]
catalog=Links();catalog.feed(get(URL))
selected=None
for link in catalog.hrefs:
 u=urllib.parse.urlsplit(urllib.parse.urljoin(URL,html.unescape(link)))
 if u.hostname not in ("pornhub.com","www.pornhub.com") or u.path!="/view_video.php":continue
 if re.fullmatch(r"[A-Za-z0-9_-]{5,50}",urllib.parse.parse_qs(u.query).get("viewkey",[""])[0]):
  selected=u.geturl();break
assert selected
raw=get(selected,URL)
print("PUB_WATCH_LEN",len(raw),flush=True)
for key in ("flashvars_","mediaDefinitions","videoUrl","mediaURL","qualityItems","playerConfig"):
 print("MATCH",key,"COUNT",raw.count(key),flush=True)
 for m in list(re.finditer(re.escape(key),raw))[:3]:
  print("REDACTED",red(raw[max(0,m.start()-170):m.end()+250]),flush=True)
for m in list(re.finditer(r"(?m)\b(?:var\s+)?flashvars_[0-9]+\s*[=:]",raw))[:4]:
 print("FLASH_ASSIGN",red(raw[m.start():m.start()+700]),flush=True)
