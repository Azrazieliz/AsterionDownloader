#!/usr/bin/env python3
"""Read-only Kyora source request check: publisher album HTML, selected photo
HTTP status under legacy and current User-Agent. Reads at most ONE media byte.
Does not download original media or print media URLs, cookies, or full titles.
"""
import urllib.request,urllib.parse,urllib.error,html.parser,re,json,collections
ROOTS={
"4KHD":"https://www.4khd.com/pages/cosplay",
"BuonDua":"https://buondua.net/albums",
"CosplayTele":"https://cosplaytele.com/",
"Kiutaku":"https://kiutaku.com/",
"PornPics":"https://www.pornpics.com/",
"Celeb.st":"https://celeb.st/feed/hot"}
OLD="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36 GalleryFlow/0.1"
NEW="Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/140 Mobile Safari/537.36 Kyora/0.6.0"
class Tags(html.parser.HTMLParser):
 def __init__(self,base):
  super().__init__();self.base=base;self.a=[];self.images=[];self.raw=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):
   self.a.append(urllib.parse.urljoin(self.base,d["href"]))
  if tag=="img":
   self.images.append(d)
  if tag=="meta" and d.get("property")=="og:image":
   self.images.append({"src":d.get("content","")})
def request(url,ua,referer=None,media=False):
 h={"User-Agent":ua,"Accept-Language":"en-US,en;q=0.8","Accept-Encoding":"identity","Accept":"image/avif,image/webp,image/apng,image/*,*/*;q=0.8" if media else "text/html,application/xhtml+xml,*/*;q=0.8"}
 if referer:h["Referer"]=referer
 # Do not send Range: verify the same full-original HTTP response used by
 # the app, without downloading the payload (read <=40 signature bytes).
 parts=urllib.parse.urlsplit(url)
 if any(ord(c)>127 for c in parts.path):
  url=urllib.parse.urlunsplit((parts.scheme,parts.netloc,
   urllib.parse.quote(parts.path,safe="/%:@-._~"),
   parts.query,parts.fragment))
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=16) as r:
   code=r.status;kind=r.headers.get("Content-Type","").split(";")[0];final=r.url
   body=r.read(40 if media else 650000)
  return code,kind,final,body
 except urllib.error.HTTPError as e:return e.code,"",e.url,b""
 except Exception as e:return type(e).__name__,"",url,b""
def isalbum(name,path):
 return bool({
  "4KHD":lambda:re.fullmatch(r"/content/[0-9]+/[A-Za-z0-9_-]+\.html",path,re.I),
  "BuonDua":lambda:re.fullmatch(r"/albums/[A-Za-z0-9_-]+/?",path,re.I),
  "CosplayTele":lambda:re.fullmatch(r"/[A-Za-z0-9_-]{3,180}/?",path,re.I),
  "Kiutaku":lambda:re.fullmatch(r"/[0-9]{1,9}/?",path,re.I),
  "PornPics":lambda:re.fullmatch(r"/galleries/[^/]+/?",path,re.I),
  "Celeb.st":lambda:re.fullmatch(r"/creator/[^/]+/images/?",path,re.I)
 }[name]())
for name,root in ROOTS.items():
 try:
  rc,rt,ru,body=request(root,OLD)
  d=Tags(ru);d.feed(body.decode("utf8","replace"))
  pages=[u for u in d.a if isalbum(name,urllib.parse.urlsplit(u).path)]
  pages=list(dict.fromkeys(pages))
  if not pages and name=="PornPics":
   for listing in ("https://www.pornpics.com/popular/","https://www.pornpics.com/recent/"):
    _,_,url,page=request(listing,OLD)
    parsed=Tags(url);parsed.feed(page.decode("utf8","replace"))
    pages=list(dict.fromkeys(u for u in parsed.a if isalbum(name,urllib.parse.urlsplit(u).path)))
    if pages:break
  out={"provider":name,"listing_http":rc,"album_links":len(pages)}
  if not pages:print(json.dumps(out),flush=True);continue
  article=pages[0];code,_,final,html=request(article,OLD,ru)
  one=Tags(final);one.feed(html.decode("utf8","replace"))
  out["selected_article_http"]=code;out["article_images"]=len(one.images)
  candidates=[]
  for im in one.images:
   for attr in ("data-original","data-full","data-full-src","data-highres","data-src","data-lazy-src","src"):
    src=im.get(attr,"")
    if not src or src.startswith("data:"):continue
    url=urllib.parse.urljoin(final,src)
    host=urllib.parse.urlsplit(url).hostname or ""
    path=urllib.parse.urlsplit(url).path
    if not re.search(r"\.(?:jpe?g|png|webp|avif)(?:$)",path,re.I):continue
    if name=="BuonDua" and (host!="cdn.buondua.net" or "/photos/" not in path):continue
    if name=="Kiutaku" and (host not in ("mitaku.net","www.mitaku.net","kiutaku.com","www.kiutaku.com") or "/wp-content/uploads/" not in path):continue
    if name=="CosplayTele" and "wp-content/uploads/" not in path:continue
    if any(x in path.lower() for x in ("avatar","logo","thumb","placeholder")):continue
    candidates.append(url);break
  out["image_candidates"]=len(set(candidates))
  if candidates:
   original=candidates[min(2,len(candidates)-1)]
   for label,ua in (("legacy",OLD),("current",NEW)):
    status,mime,_,_=request(original,ua,final,True)
    out[label+"_original_status"]=status;out[label+"_original_mime"]=mime
  print(json.dumps(out),flush=True)
 except Exception as e: print(json.dumps({"provider":name,"error":type(e).__name__,"detail":str(e)[:80]}),flush=True)
