#!/usr/bin/env python3
"""Read-only exact selected Archivebate watch player and canonical redirect.

Only actual selected /watch/16458050 (the real source previously included
in verified parser fixtures), not recommendation previews or related videos.
Static publisher HTML and Edge-rendered selected iframe are compared.
No user session, ads, captchas or DRM bypass; no media download claims.
"""
import json,re,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path
WATCH="https://archivebate.com/watch/16458050"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.frames=[];self.videos=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
  if tag=="video":self.videos.append(a)
def selected(url):
 u=urllib.parse.urlsplit(urllib.parse.urljoin(WATCH,url))
 if u.scheme!="https" or u.hostname not in ("mixdrop.ag","www.mixdrop.ag"):return False
 return bool(re.fullmatch(r"/e/[A-Za-z0-9_-]{5,64}/?",u.path))
def browser():
 with tempfile.TemporaryDirectory(prefix="asterion_selected_archivebate_") as folder:
  args=[str(EDGE),"--headless=new","--disable-gpu","--no-first-run",
    "--no-default-browser-check","--disable-extensions",
    "--disable-background-networking","--user-data-dir="+folder,
    "--virtual-time-budget=11000","--dump-dom",WATCH]
  p=subprocess.run(args,capture_output=True,timeout=30)
  return p.returncode,p.stdout.decode("utf8","replace")
def main():
 req=urllib.request.Request(WATCH,headers={
  "User-Agent":UA,"Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=23) as r:
   status=r.status;page=r.read(1200000).decode("utf8","replace")
 except urllib.error.HTTPError as error:
  print(json.dumps({"stage":"selected_publisher_watch",
    "http":error.code,"status":"PUBLISHER_DENIED"}),flush=True)
  return 2
 f=Frames();f.feed(page)
 accepted=list(dict.fromkeys(urllib.parse.urljoin(WATCH,x) for x in f.frames if selected(x)))
 print(json.dumps({"stage":"selected_static_watch",
  "http":status,"html_bytes":len(page),
  "all_iframes":len(f.frames),"publisher_mixdrop_frames":len(accepted),
  "video_elements":len(f.videos),
  "verification_gate":bool(re.search(r"just a moment|verify you are human|cf-turnstile",page,re.I))}),flush=True)
 if len(accepted)!=1 and EDGE.exists():
  try:
   code,rendered=browser()
   f=Frames();f.feed(rendered)
   accepted=list(dict.fromkeys(urllib.parse.urljoin(WATCH,x) for x in f.frames if selected(x)))
   print(json.dumps({"stage":"selected_rendered_watch",
     "edge_exit":code,"dom_bytes":len(rendered),
     "publisher_mixdrop_frames":len(accepted),
     "other_frames":len(f.frames)-len(accepted),
     "verification_gate":bool(re.search(r"just a moment|verify you are human|cf-turnstile",rendered[:20000],re.I))}),flush=True)
  except Exception as ex:
   print(json.dumps({"stage":"selected_rendered_watch",
     "error":type(ex).__name__}),flush=True)
 if len(accepted)!=1:return 3
 embed=accepted[0]
 print(json.dumps({"stage":"exact_selected_publisher_frame",
   "embedded_host":urllib.parse.urlsplit(embed).hostname,
   "route_format_valid":True}),flush=True)
 try:
  with urllib.request.urlopen(urllib.request.Request(embed,headers={
   "User-Agent":UA,"Referer":WATCH,"Accept-Encoding":"identity"}),timeout=23) as response:
   final=response.url
   original=urllib.parse.urlsplit(embed);redirect=urllib.parse.urlsplit(final)
   same=redirect.path==original.path and redirect.hostname in ("mixdrop.ag","mxdrop.click")
   data=response.read(300000).decode("utf8","replace")
   print(json.dumps({"stage":"exact_embedded_player",
     "http":response.status,"final_host":redirect.hostname,
     "same_id_redirect":same,"html_bytes":len(data),
     "video_element":bool(re.search("<video\\b",data,re.I)),
     "direct_mp4_tokens":len(re.findall("\\.mp4",data,re.I)),
     "gate":bool(re.search("captcha|verify you are human",data[:15000],re.I))}),flush=True)
   return 0 if same else 4
 except urllib.error.HTTPError as error:
  print(json.dumps({"stage":"exact_embedded_player",
   "http":error.code,"status":"HOSTER_DENIED"}),flush=True)
  return 5
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as ex:
  print(json.dumps({"status":"SELECTED_SOURCE_NOT_VERIFIED",
   "error":type(ex).__name__,"reason":str(ex)[:130]}),flush=True)
  raise SystemExit(6)
