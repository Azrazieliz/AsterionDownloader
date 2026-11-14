#!/usr/bin/env python3
"""Site-specific first-party player contract probes. No media files requested.

Only bounded, exactly advertised publisher-selected iframe URLs are fetched.
No browser DOM scraping, download detection, cookies, or bypasses.
"""
import json,re,urllib.request,urllib.parse,urllib.error
from html.parser import HTMLParser
from collections import Counter

CASES={
 "fullporner.com":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
 "hqpornerxxx.com":"https://hqpornerxxx.com/videos/keiranlee-less-talking-more-knocking-39904.html",
 "uncensoredhentai.xxx":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
 "javideo.net":"https://javideo.net/en/fc2ppv-4994603",
}
ALLOWED={
 "fullporner.com":("fullporner.com",r"^/player/[0-9]+/[0-9]+/?$"),
 "hqpornerxxx.com":("myxstudio.top",r"^/embed/[A-Za-z0-9_-]+/?$"),
 "uncensoredhentai.xxx":("nhplayer.com",r"^/v/[A-Za-z0-9]+/?$"),
 "javideo.net":("javideo.net",r"^/en/[^?#]+$"),
}
class P(HTMLParser):
 def __init__(self):super().__init__();self.frames=[];self.sources=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):self.frames.append(a)
  if tag in ("video","source") and a.get("src"):self.sources.append(a)
  if tag=="script" and a.get("src"):self.scripts.append(a["src"])
def retrieve(address,ref=None):
 headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept":"text/html","Accept-Encoding":"identity"}
 if ref:headers["Referer"]=ref
 req=urllib.request.Request(address,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=15) as r:return r.status,r.url,r.read(1500000).decode("utf8","replace")
 except urllib.error.HTTPError as err:return err.code,address,""
 except Exception as err:return type(err).__name__,address,""
def patterns(s):
 return {k:len(re.findall(p,s,re.I)) for k,p in {
    "mp4":r"\.mp4\b","hls":r"\.m3u8\b","dash":r"\.mpd\b",
    "video_url":r"video_url|videoUrl|video_url_alt",
    "jw":r"jwplayer|jw-player|playerSetup","videojs":r"videojs|video-js",
    "file":r"(?:file|src|source|url)\s*[:=]\s*[\"'](?:https?:)?//",
    "packed":r"eval\(function\(p,a,c,k,e,d",
    "redirect":r"window.location|location.href",
    "cloudflare":r"Just a moment|Cloudflare|verify you are human",
 }.items()}
def one(entry):
 host,page=entry
 code,addr,root=retrieve(page)
 result={"host":host,"selected_status":code,"frames":[]}
 if not root:return result
 parser=P();parser.feed(root)
 safe,shape=ALLOWED[host]
 for frame in parser.frames:
  url=urllib.parse.urljoin(addr,frame["src"])
  uri=urllib.parse.urlsplit(url)
  if uri.scheme!="https" or uri.hostname not in (safe,"www."+safe) or not re.fullmatch(shape,uri.path):continue
  if host=="javideo.net" and url.split("?")[0].rstrip("/")==page.rstrip("/"):
   result["frames"].append({"type":"self_iframe","path":uri.path})
   continue
  response,actual,html=retrieve(url,addr)
  parsed=P();parsed.feed(html)
  result["frames"].append({"type":"publisher_selected","host":safe,"path":uri.path[:70],
    "status":response,"html_chars":len(html),
    "player_source_tags":len(parsed.sources),"nested_frames":len(parsed.frames),
    "script_hosts":dict(Counter(urllib.parse.urlsplit(urllib.parse.urljoin(url,s)).hostname for s in parsed.scripts).most_common(6)),
    "signals":patterns(html)})
 return result
if __name__=="__main__":
 from concurrent.futures import ThreadPoolExecutor
 with ThreadPoolExecutor(max_workers=4) as pool:
  for result in pool.map(one,CASES.items()):print(json.dumps(result),flush=True)
