#!/usr/bin/env python3
"""Read-only source/album request probe; no adult image/video bytes downloaded.
Print only host,status,redirect_host,matching album counts and image attribute counts.
No cookies, links, titles or content exported.
"""
import urllib.request,urllib.parse,urllib.error,html.parser,re,json,time
class Count(html.parser.HTMLParser):
 def __init__(self,match):
  super().__init__();self.match=match;self.album=set();self.images=0;self.explicit=0;self.title=0
 def handle_starttag(self,t,attrs):
  d=dict(attrs)
  if t=="a":
   p=urllib.parse.urlsplit(urllib.parse.urljoin(self.base,d.get("href",""))).path
   if self.match(p):self.album.add(p)
  if t=="img":
   self.images+=1
   if any(k in d for k in ("data-original","data-full","data-src","data-highres","srcset")):self.explicit+=1
  if t=="h1":self.title+=1
def check(host,url,rule):
 report={"host":host}
 try:
  req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Language":"en-US,en;q=0.8","Accept-Encoding":"identity"})
  with urllib.request.urlopen(req,timeout=18) as r:
   b=r.read(700000);end=r.url;status=r.status
  p=Count(rule);p.base=end;p.feed(b.decode("utf-8","replace"))
  report.update({"status":status,"redirect_host":urllib.parse.urlsplit(end).hostname,"bytes_scanned":len(b),"gallery_links":len(p.album),"img_nodes":p.images,"lazy_original_attributes":p.explicit,"article_h1":p.title})
 except urllib.error.HTTPError as e:report.update({"status":e.code,"redirect_host":urllib.parse.urlsplit(e.url).hostname})
 except Exception as e:report.update({"error_type":type(e).__name__,"error_detail":str(e)[:100]})
 print(json.dumps(report),flush=True)
sites=[
 ("4khd.com","https://www.4khd.com/pages/cosplay",lambda p:re.fullmatch(r"/content/[0-9]{1,8}/[A-Za-z0-9_-]+\\.html",p,re.I) is not None),
 ("buondua.net","https://buondua.net/albums",lambda p:p.startswith("/albums/") and len(p.split("/"))>=3),
 ("cosplaytele.com","https://cosplaytele.com/",lambda p:re.fullmatch(r"/[A-Za-z0-9_-]{3,180}/?",p) is not None),
 ("kiutaku.com","https://kiutaku.com/",lambda p:re.fullmatch(r"/[0-9]{1,9}/?",p) is not None),
 ("pornpics.com","https://www.pornpics.com/",lambda p:p.startswith("/galleries/")),
 ("celeb.st","https://celeb.st/feed/hot",lambda p:re.fullmatch(r"/(?:p|post|posts|gallery|photos|media)/[^/]+/?",p) is not None)
]
for h,u,rule in sites:check(h,u,rule)
