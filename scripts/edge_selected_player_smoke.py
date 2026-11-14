#!/usr/bin/env python3
"""Bounded Edge smoke check of selected players. Never save media or use network interception.
Runs existing desktop Edge in headless mode and reports only DOM media source counts, no URLs.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from html.parser import HTMLParser
from pathlib import Path
import subprocess,tempfile,time,urllib.parse,re,os,sys
EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
TARGETS={
 "FullPorner":"https://fullporner.com/player/9509933/14",
 "HQPorner":"https://myxstudio.top/embed/5d8297e451a0e650ca",
 "UncensoredHentai":"https://nhplayer.com/v/Z2flAXXPucyCbVJ/",
 "JAVIDEO":"https://javideo.net/en/fc2ppv-4994603",
 "HentaiBros":"https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
 "3DHentai":"https://3dhentaix.com/ntr-compilation/",
 "HentaiHaven":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
 "HelloPornVoe":"https://voe.sx/e/wx8walm5hqkj",
}
class Selected(HTMLParser):
 def __init__(self):
  super().__init__();self.videos=[];self.sources=[];self.frames=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="video":self.videos.append(a)
  elif tag=="source":self.sources.append(a)
  elif tag=="iframe":self.frames.append(a)
def inspect(item):
 name,url=item
 with tempfile.TemporaryDirectory(prefix="asterion_edge_profile_") as d:
  args=[EDGE,"--headless=new","--disable-gpu","--no-first-run","--no-default-browser-check",
        "--disable-extensions","--mute-audio","--disable-background-networking",
        "--autoplay-policy=user-gesture-required","--user-data-dir="+d,
        "--virtual-time-budget=9000","--dump-dom",url]
  try:
   result=subprocess.run(args,capture_output=True,timeout=28)
   page=result.stdout.decode('utf-8','replace')
   p=Selected();p.feed(page)
   raw=sum(1 for n in p.videos if (n.get("src","") or n.get("data-src","")).startswith("http"))
   videos=[{"src_type": ("https" if (n.get("src","") or "").startswith("https:") else
                                "blob" if (n.get("src","") or "").startswith("blob:") else
                                "none"),
            "duration":n.get("data-duration","")[:12],
            "source_count":len([s for s in p.sources if s.get("src")])}
            for n in p.videos[:3]]
   hosts=[]
   for f in p.frames:
    host=urllib.parse.urlsplit(urllib.parse.urljoin(url,f.get("src",""))).hostname
    if host and host not in hosts:hosts.append(host)
   return (name,{"exit":result.returncode,"dom_chars":len(page),
      "videos":len(p.videos),"sources":len(p.sources),
      "valid_https_videos":raw,"video_info":videos,
      "iframe_hosts":hosts[:4],
      "site_gate":bool(re.search(r"(?i)just a moment|verify you are human|not available in your country",page[:200000]))})
  except Exception as e:return (name,{"error":type(e).__name__+":"+str(e)[:80]})
if __name__=="__main__":
 targets=[v for k,v in TARGETS.items() if len(sys.argv)==1 or k in sys.argv[1:]]
 pairs=[(k,v) for k,v in TARGETS.items() if v in targets]
 with ThreadPoolExecutor(max_workers=3) as pool:
  jobs=[pool.submit(inspect,p) for p in pairs]
  for future in as_completed(jobs):
   name,result=future.result()
   print(name,result,flush=True)
