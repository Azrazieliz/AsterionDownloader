#!/usr/bin/env python3
"""Public first-party link/player evidence for implementing distinct adapters.
This diagnostic never requests media files, user sessions or protected APIs.
"""
import concurrent.futures,html,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser

CASES={
"hello_home":"https://helloporn.com/",
"hq_selected":"https://hqpornerxxx.com/videos/keiranlee-less-talking-more-knocking-39904.html",
"roshy_selected":"https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/",
"fullporner_selected":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
"javideo_selected":"https://javideo.net/en/fc2ppv-4994603",
"mp4hentai_home":"https://mp4hentai.com/",
"hentaibros_selected":"https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
"hentaihaven_selected":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
"3dhentaix_selected":"https://3dhentaix.com/ntr-compilation/",
}
class P(HTMLParser):
 def __init__(self):
  super().__init__();self.anchors=[];self.vid=[];self.frames=[];self.current=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a":
   self.current={"href":a.get("href",""),"text":"","video":False,
                 "attrs":a};self.anchors.append(self.current)
  if tag=="video":
   if self.current is not None:self.current["video"]=True
   self.vid.append(a)
  if tag=="source":self.vid.append(a)
  if tag=="iframe":self.frames.append(a)
 def handle_data(self,s):
  if self.current is not None and len(self.current["text"])<120:self.current["text"]+=s[:120]
 def handle_endtag(self,tag):
  if tag=="a":self.current=None
def shape(base,raw):
 try:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(base,html.unescape(raw)))
  return (u.hostname or "")+" "+re.sub(r"/[a-z0-9]{26,}(?=[/?]|$)","/{slug}",re.sub(r"/[0-9]{5,}(?=[/?]|$)","/{id}",u.path))[:105]
 except:return ""
def analyze(pair):
 name,url=pair
 try:
  req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"})
  with urllib.request.urlopen(req,timeout=16) as r:
   code=r.status;raw=r.read(1600000).decode("utf8","replace");url=r.url
  p=P();p.feed(raw)
  selected=[a for a in p.anchors if a["video"]]
  relevant=[a for a in p.anchors if re.search(r"(?i)\\.mp4|\\.m3u8|\\.mpd|/download/|/get_file/|magnet:|download=true",a["href"]) or re.search(r"(?i)^download|original|mp4",a["text"].strip())]
  return name,code,{
   "video_card_links":[shape(url,a["href"]) for a in selected[:9]],
   "direct_anchors":[{"link":shape(url,a["href"]),"label":a["text"].strip()[:35]} for a in relevant[:14]],
   "iframes":[{"link":shape(url,a.get("src","")),"class":a.get("class","")[:35]} for a in p.frames[:7]],
   "video_sources":[shape(url,a.get("src","")) for a in p.vid[:7]],
   "counts":{"anchor":len(p.anchors),"video":len(p.vid),"relevant":len(relevant)}
  }
 except Exception as e:return name,type(e).__name__,{}
if __name__=="__main__":
 cases=[v for k,v in CASES.items() if len(sys.argv)==1 or k in sys.argv[1:]]
 choices=[(k,u) for k,u in CASES.items() if u in cases]
 with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
  for name,code,info in pool.map(analyze,choices):
   print(name,"HTTP",code,info,flush=True)
