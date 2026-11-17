#!/usr/bin/env python3
"""Read-only selected Archivebate Mixdrop player source after real Edge render.

No automatic media download or verification bypass. Keep tokens and URL paths
private. This is the exact selected iframe on real /watch/16458050.
"""
import json,re,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from pathlib import Path
from html.parser import HTMLParser
WATCH="https://archivebate.com/watch/16458050"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
class Source(HTMLParser):
 def __init__(self):super().__init__();self.frames=[];self.media=[];self.types=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="iframe" and a.get("src"):self.frames.append(a["src"])
  if t in ("video","source"):
   for x in ("src","data-src","poster"):
    if a.get(x):self.media.append((t,x,a[x]))
def host(url,base):
 u=urllib.parse.urlsplit(urllib.parse.urljoin(base,url))
 return u.hostname or ""
def read(url,ref):
 q=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(q,timeout=22) as r:
  return r.status,r.url,r.read(250000).decode("utf8","replace")
def main():
 status,url,html=read(WATCH,WATCH)
 p=Source();p.feed(html)
 links=list(dict.fromkeys(urllib.parse.urljoin(WATCH,x) for x in p.frames if
   host(x,WATCH)=="mixdrop.ag" and re.search(r"/e/[A-Za-z0-9_-]{5,64}/?$",urllib.parse.urlsplit(x).path)))
 assert len(links)==1
 src=links[0]
 status,redirect,markup=read(src,WATCH)
 u1=urllib.parse.urlsplit(src);u2=urllib.parse.urlsplit(redirect)
 assert status==200 and u2.hostname in ("mixdrop.ag","mxdrop.click") and u1.path==u2.path
 p2=Source();p2.feed(markup)
 print(json.dumps({"stage":"canonical_selected_player",
  "http":status,"selected_host":u2.hostname,
  "raw_video_nodes":len([x for x in p2.media if x[0]=="video"]),
  "raw_media_host_candidates":list(sorted(set(host(x[2],redirect) for x in p2.media))),
  "inline_scripts":markup.count("<script"),"packed_script":bool(re.search(r"eval\(function\(p,a,c,k,e,d\)",markup)),
  "contains_html5_video":bool(re.search(r"<video\b",markup,re.I))}),flush=True)
 assert EDGE.is_file()
 with tempfile.TemporaryDirectory(prefix="asterion_ab_selected_") as d:
  args=[str(EDGE),"--headless=new","--disable-gpu","--disable-extensions",
    "--disable-background-networking","--no-first-run","--no-default-browser-check",
    "--autoplay-policy=no-user-gesture-required","--virtual-time-budget=11000",
    "--user-data-dir="+d,"--dump-dom",redirect]
  p=subprocess.run(args,capture_output=True,timeout=35)
  rendered=p.stdout.decode("utf8","replace")
 parsed=Source();parsed.feed(rendered)
 print(json.dumps({"stage":"rendered_selected_player",
  "browser_exit":p.returncode,"dom_bytes":len(rendered),
  "media_nodes":len(parsed.media),"video_sources":[
    {"node":t,"field":f,"host":host(v,redirect),
      "is_media":bool(re.search(r"\.(mp4|m4v)(\?|$)",urllib.parse.urlsplit(v).path,re.I))}
    for t,f,v in parsed.media[:12]],
  "video_current_src_literal":rendered.count("currentSrc"),
  "generic_gate_script_mentions":bool(re.search(r"captcha|turnstile",rendered,re.I))}),flush=True)
 return 0
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as e:
  print(json.dumps({"status":"ARCHIVEBATE_RENDER_FAILED",
    "type":type(e).__name__,"reason":str(e)[:180]}),flush=True)
  raise SystemExit(3)
