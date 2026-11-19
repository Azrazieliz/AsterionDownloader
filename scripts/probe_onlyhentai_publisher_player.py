#!/usr/bin/env python3
"""Read-only structural probe of a currently published OnlyHentaiStuff player.

No media download, paywall bypass, login, or ads. Reports only hostname,
HTTP status, and page/player structure; never titles, URLs, cookies, or tokens.
"""
from html.parser import HTMLParser
from urllib.parse import urlsplit,urljoin
import urllib.request,json,re,sys,ssl

ROOT="https://onlyhentaistuff.com/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

class Page(HTMLParser):
 def __init__(self):
  super().__init__();self.links=[];self.frames=[];self.videos=[];self.metadata=[];self.sources=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.links.append(a["href"])
  if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
  if tag=="video":self.videos.append(a)
  if tag=="source" and a.get("src"):self.sources.append(a["src"])
  if tag=="meta" and a.get("property") in ("og:video","og:video:url"):self.metadata.append(a.get("content",""))
def read(url,referer=None):
 headers={"User-Agent":UA,"Accept":"text/html,*/*;q=0.5","Accept-Encoding":"identity"}
 if referer:headers["Referer"]=referer
 req=urllib.request.Request(url,headers=headers)
 with urllib.request.urlopen(req,timeout=18) as r:
  code=r.status
  mime=r.headers.get("Content-Type","")
  text=r.read(1200000).decode("utf-8","replace")
  return code,mime,text
def structure(url,code,mime,raw):
 dom=Page();dom.feed(raw)
 return {
  "host":urlsplit(url).hostname,"status":code,"mime":mime.split(";")[0],
  "html_bytes":len(raw),"iframe_count":len(dom.frames),
  "iframe_hosts":sorted({urlsplit(urljoin(url,x)).hostname for x in dom.frames}),
  "html5_video_count":len(dom.videos),"html5_source_count":len(dom.sources),
  "structured_og_video_count":len(dom.metadata),
  "kvs_flashvars":bool(re.search(r"\\bflashvars\\s*=",raw,re.I)),
  "kt_player":bool(re.search(r"kt_player",raw,re.I)),
  "jwplayer":bool(re.search(r"\\bjwplayer\\b",raw,re.I)),
  "videojs":bool(re.search(r"video\\.js|videojs",raw,re.I))
 }
def main():
 try:
  code,mime,raw=read(ROOT)
  home=Page();home.feed(raw)
  links=[]
  for x in home.links:
   url=urljoin(ROOT,x)
   u=urlsplit(url)
   if (u.scheme=="https" and u.hostname in
      ("onlyhentaistuff.com","www.onlyhentaistuff.com") and
      re.fullmatch(r"/(?:videos|video|watch)/[^/?#]+/?",u.path,re.I)
      and not u.query):
    if url not in links:links.append(url)
  print(json.dumps({"probe":"ONLYHENTAI_HOME",**structure(ROOT,code,mime,raw),
      "selected_permalink_count":len(links)}),flush=True)
  if not links:
   from collections import Counter
   own=[urlsplit(urljoin(ROOT,x)).path for x in home.links
        if urlsplit(urljoin(ROOT,x)).hostname in
           ("onlyhentaistuff.com","www.onlyhentaistuff.com")]
   kinds=Counter((p.strip("/").split("/")[0][:32] or "root",
                  len([x for x in p.split("/") if x])) for p in own)
   print(json.dumps({"probe":"ONLYHENTAI_LINK_SHAPES",
       "first_segment_path_depth_counts":[
          {"section":k[0],"depth":k[1],"count":v}
          for k,v in kinds.most_common(35)],
       "total_own_links":len(own),
       "publisher_original_verified":False}),flush=True)
   return 2
  for url in links[:2]:
   try:
    code,mime,raw=read(url,ROOT)
    print(json.dumps({"probe":"ONLYHENTAI_SELECTED",**structure(url,code,mime,raw),
        "publisher_original_verified":False}),flush=True)
   except Exception as error:
    print(json.dumps({"probe":"ONLYHENTAI_SELECTED","error_type":type(error).__name__,
        "publisher_original_verified":False}),flush=True)
  return 0
 except Exception as error:
  print(json.dumps({"probe":"ONLYHENTAI","result":"UNREACHABLE",
      "error_type":type(error).__name__,
      "publisher_original_verified":False}),flush=True)
  return 3
if __name__=="__main__":sys.exit(main())
