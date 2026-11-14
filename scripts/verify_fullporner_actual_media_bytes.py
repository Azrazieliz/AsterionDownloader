#!/usr/bin/env python3
"""Verify exact selected FullPorner JS-generated MP4 links by HTTP Range.
Uses preinstalled Edge. No full downloads, no copied URLs or content retained.
"""
import subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PLAYER="https://fullporner.com/player/9509933/14"
class Player(HTMLParser):
 def __init__(self):super().__init__();self.sources=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="source" and a.get("src"):self.sources.append(a)
with tempfile.TemporaryDirectory(prefix="asterion_fullporner_proof_") as root:
 p=subprocess.run([EDGE,"--headless=new","--disable-gpu","--mute-audio",
  "--disable-extensions","--no-first-run","--user-data-dir="+root,
  "--virtual-time-budget=7000","--dump-dom",PLAYER],
  capture_output=True,timeout=25)
 html=p.stdout.decode("utf-8","replace")
 doc=Player();doc.feed(html)
 print("DOM",p.returncode,len(html),"source_count",len(doc.sources),flush=True)
 seen=set()
 for src in doc.sources[:4]:
  uri=urllib.parse.urlsplit(urllib.parse.urljoin(PLAYER,src["src"]))
  if (uri.scheme!="https" or
      uri.hostname not in ("xiaoshenke.net","www.xiaoshenke.net") or
      not uri.path.startswith("/vid/")):continue
  if uri.geturl() in seen:continue
  seen.add(uri.geturl())
  req=urllib.request.Request(uri.geturl(),headers={
    "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
    "Referer":PLAYER,"Range":"bytes=0-255","Accept-Encoding":"identity"})
  try:
   with urllib.request.urlopen(req,timeout=13) as r:
    chunk=r.read(256)
    print("type",src.get("type",""),"label",src.get("label",""),
      "host",uri.hostname,"path_segments",len([z for z in uri.path.split("/") if z]),
      "status",r.status,"mime",r.headers.get("Content-Type","").split(";")[0],
      "range",r.headers.get("Content-Range","")[:60],
      "ftyp",chunk[4:8]==b"ftyp","bytes_read",len(chunk),flush=True)
  except urllib.error.HTTPError as e:
    print("HTTP",e.code,"host",uri.hostname,"path_prefix","/".join(uri.path.split("/")[:3]),flush=True)
  except Exception as e:
    print("ERROR",type(e).__name__,flush=True)
