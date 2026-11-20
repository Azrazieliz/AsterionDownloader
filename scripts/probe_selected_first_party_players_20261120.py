#!/usr/bin/env python3
"""Read-only selected video player structure audit; no media bytes downloaded."""
import urllib.request,urllib.parse,urllib.error,html.parser,re,json
SITES={
"PimpBunny":("https://pimpbunny.com/",r"^/videos/[a-z0-9-]{4,240}/?$"),
"xChina":("https://en.xchina.co/",r"^/video/[a-f0-9]{10,30}/?$"),
"Bubblecum":("https://www.bubblecum.lol/",r"^/video/[a-z0-9-]{7,220}/?$"),
"PornAVHD":("https://pornavhd.com/",r"^/20[0-9]{2}/[0-9]{1,2}/[0-9]{1,2}/[^/]+/?$"),
"CamStreams":("https://camstreams.tv/latest-updates/",r"^/videos/[0-9]{4,12}/[a-z0-9-]{5,240}/?$")}
class Audit(html.parser.HTMLParser):
 def __init__(self,base):
  super().__init__();self.base=base;self.links=[];self.videos=[];self.sources=[];self.frames=[];self.meta=[];self.download=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):
   url=urllib.parse.urljoin(self.base,d["href"]);self.links.append(url)
   if d.get("download") is not None or "download" in d.get("href","").lower():self.download.append(url)
  if tag=="video":self.videos.append(d)
  if tag=="source":self.sources.append(d)
  if tag=="iframe":self.frames.append(d)
  if tag=="meta" and d.get("property","").lower() in ("og:video","og:video:url","og:image"):self.meta.append(d)
def fetch(url,referer=None):
 h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept":"text/html,application/xhtml+xml","Accept-Encoding":"identity","Accept-Language":"en-US,en;q=0.9"}
 if referer:h["Referer"]=referer
 req=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(req,timeout=18) as r:
  return r.status,r.url,r.read(900000).decode("utf8","replace")
for name,(home,expr) in SITES.items():
 out={"provider":name}
 try:
  code,base,html=fetch(home)
  p=Audit(base);p.feed(html)
  baseHost=urllib.parse.urlsplit(base).hostname.removeprefix("www.")
  pages=list(dict.fromkeys(url for url in p.links if
    urllib.parse.urlsplit(url).hostname and
    urllib.parse.urlsplit(url).hostname.removeprefix("www.")==baseHost and
    re.match(expr,urllib.parse.urlsplit(url).path,re.I)))
  out["listed_video_pages"]=len(pages)
  if not pages:print(json.dumps(out),flush=True);continue
  selected=pages[0];status,url,detail=fetch(selected,home)
  q=Audit(url);q.feed(detail)
  out.update({"watch_http":status,
    "route":urllib.parse.urlsplit(url).path,
    "watch_html_length":len(detail),
    "html5_videos":len(q.videos),
    "html5_sources":len(q.sources),
    "iframes":len(q.frames),
    "iframe_hosts":list(dict.fromkeys(urllib.parse.urlsplit(
        urllib.parse.urljoin(url,x.get("src",""))).hostname for x in q.frames))[:8],
    "poster_metas":sum(1 for x in q.meta if x.get("property")=="og:image"),
    "video_metas":sum(1 for x in q.meta if x.get("property","").startswith("og:video")),
    "downloads":len(q.download),
    "movie_content_url":len(re.findall(r"(?i)contentUrl",detail)),
    "hls_mentions":len(re.findall(r"(?i)\.m3u8",detail)),
    "file_mp4_mentions":len(re.findall(r"(?i)\.mp4",detail)),
    "published_duration_fields":len(re.findall(r"(?i)(?:duration|length|runtime)",detail)),
    "video_src_hosts":list(dict.fromkeys(urllib.parse.urlsplit(urllib.parse.urljoin(url,x.get("src",""))).hostname for x in q.videos+q.sources if x.get("src")))[:6]})
  print(json.dumps(out),flush=True)
 except urllib.error.HTTPError as e:out["watch_http"]=e.code;print(json.dumps(out),flush=True)
 except Exception as e:out.update({"error":type(e).__name__,"detail":str(e)[:70]});print(json.dumps(out),flush=True)
