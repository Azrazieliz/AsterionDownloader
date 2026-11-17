#!/usr/bin/env python3
"""Inspect selected Hentaibros rendered DOM without claiming any full download."""
import json,re,subprocess,tempfile,urllib.parse
from html.parser import HTMLParser

EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
POST="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
class Scene(HTMLParser):
 def __init__(self):
  super().__init__();self.stack=[];self.players=[];self.videos=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);cls=a.get("class","")
  excluded=any(c["excluded"] for c in self.stack) or tag=="aside" or any(
   name in cls.lower() for name in ("related","sidebar","recommended","advertisement","ads"))
  player_here=bool(re.fullmatch(r"wpfp_\d{1,10}",a.get("id",""))) or (
   a.get("data-item") and "flowplayer" in cls)
  if player_here:self.players.append({"has_config":bool(a.get("data-item")),"excluded":excluded})
  if tag=="video":
   src=a.get("src","")
   parsed=urllib.parse.urlsplit(src)
   inflow=any("flowplayer" in c["class"] for c in self.stack)
   self.videos.append({"src_host":parsed.hostname or "","in_flowplayer":inflow,
       "excluded":excluded,"duration_attr":a.get("data-duration",""),
       "within_video_player":any("video-player" in c["class"] for c in self.stack)})
  if tag not in ("source","img","br","input","hr","meta","link","wbr","area"):
   self.stack.append({"tag":tag,"class":cls,"excluded":excluded})
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i]["tag"]==tag:
    del self.stack[i:];break
def main():
 with tempfile.TemporaryDirectory(prefix="asterion_hentaibros_dom_") as d:
  p=subprocess.run([EDGE,"--headless=new","--disable-gpu","--mute-audio",
   "--disable-extensions","--no-first-run","--user-data-dir="+d,
   "--virtual-time-budget=9000","--dump-dom",POST],capture_output=True,timeout=35)
  body=p.stdout.decode("utf8","replace")
  scan=Scene();scan.feed(body)
  result={"edge_exit":p.returncode,"dom_length":len(body),
   "player_config_count":len(scan.players),"configured":sum(x["has_config"] for x in scan.players),
   "excluded_configs":sum(x["excluded"] for x in scan.players),
   "videos":scan.videos[:12]}
  print(json.dumps(result,ensure_ascii=False),flush=True)
  return 0 if len(body)>1000 else 2
if __name__=="__main__":raise SystemExit(main())
