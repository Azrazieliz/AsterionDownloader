#!/usr/bin/env python3
"""Inspect only official user-selected iframe players. No download, browser
request sniffing, credentials, signed URLs or media-byte retrieval.
"""
import concurrent.futures,html,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
PAGES={
"fullporner.com":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
"hqpornerxxx.com":"https://hqpornerxxx.com/videos/keiranlee-less-talking-more-knocking-39904.html",
"uncensoredhentai.xxx":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
"javideo.net":"https://javideo.net/en/fc2ppv-4994603",
}
ALLOWED={
"fullporner.com":{"fullporner.com"},
"hqpornerxxx.com":{"myxstudio.top"},
"uncensoredhentai.xxx":{"nhplayer.com"},
"javideo.net":{"javideo.net"},
}
class Tags(HTMLParser):
 def __init__(self):super().__init__();self.frames=[];self.vid=[];self.meta=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):self.frames.append(a)
  if tag in ("video","source") and a.get("src"):self.vid.append(a)
  if tag=="meta" and a.get("content"):self.meta.append(a)
def get(url,referer=None):
 req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Referer":referer or url,"Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=15) as r:
   return r.status,r.url,r.read(1000000).decode("utf8","replace")
 except urllib.error.HTTPError as e:return e.code,url,""
 except Exception as e:return type(e).__name__,url,""
def shape(url):
 u=urllib.parse.urlsplit(url)
 return (u.hostname or "")+" "+re.sub(r"/[A-Za-z0-9_-]{16,}","/{id}",u.path)[:85]
def probe(pair):
 domain,page=pair
 out=[domain];http,final,body=get(page)
 out.append("page_http="+str(http))
 p=Tags();p.feed(body)
 selected=[]
 for f in p.frames:
  url=urllib.parse.urljoin(final,html.unescape(f.get("src","")))
  uri=urllib.parse.urlsplit(url)
  if uri.scheme!="https" or (uri.hostname or "").removeprefix("www.") not in ALLOWED[domain]:continue
  if domain=="fullporner.com" and not re.match(r"^/player/\d+/\d+/?$",uri.path):continue
  if domain=="hqpornerxxx.com" and not re.match(r"^/embed/[A-Za-z0-9_/-]+",uri.path):continue
  if domain=="uncensoredhentai.xxx" and not re.match(r"^/v/[A-Za-z0-9_-]{8,64}/?$",uri.path):continue
  if domain=="javideo.net" and url.rstrip("/") ==page.rstrip("/"):continue
  selected.append(url)
 out.append("approved_iframes="+str(len(selected)))
 for nested in selected[:2]:
  status,url,source=get(nested,page)
  player=Tags();player.feed(source)
  direct=[shape(urllib.parse.urljoin(url,v["src"])) for v in player.vid]
  hints={name:len(re.findall(pattern,source,re.I)) for name,pattern in {
   "videojs":r"videojs|jwplayer|\bplayer_data\b|\bflashvars\b",
   "manifest":r"\.m3u8|\.mpd","mp4":r"\.mp4",
   "source":r"\bsources?\s*[:=]","file":r"\bfile\s*[:=]",
   "gate":r"just a moment|captcha|access denied|verify you are human"}.items()}
  snippets=[]
  for match in list(re.finditer(r"(?i)\\.mp4|\\bsources\\b|\\bfile\\b|\\bvideoUrl\\b",source))[:10]:
   context=source[max(0,match.start()-70):min(len(source),match.end()+90)]
   context=re.sub(r"https?://[^\\s'\\\"<>]+","[URL]",context)
   context=re.sub(r"[A-Za-z0-9_-]{50,}","[TOKEN]",context)
   snippets.append(re.sub(r"[\\r\\n\\t]+"," ",context)[:140])
  out.append("player="+shape(nested)+" http="+str(status)+
    " bytes="+str(len(source))+" actual_video_tags="+str(direct[:3])+" hints="+str(hints)+
    " snippets="+str(snippets[:7]))
 return " | ".join(out)
if __name__=="__main__":
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  for result in pool.map(probe,PAGES.items()):print(result,flush=True)
