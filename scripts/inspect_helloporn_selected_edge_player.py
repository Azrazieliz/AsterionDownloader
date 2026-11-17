#!/usr/bin/env python3
"""Read-only existing Edge check of actual HelloPorn-selected VOE iframe.

URL is taken only from first-party selected /video-74763-...; no fake
original, no advertising or unrelated thumbnail. Edge uses existing local
browser network stack, no login, CAPTCHA solving or plugin installs.
"""
import json,re,shutil,subprocess,tempfile,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path
PAGE="https://helloporn.com/video-74763-nurumassage-170407privatepracticemassage-s01-ericmasterson-summerday-j"
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.frames=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
with urllib.request.urlopen(urllib.request.Request(PAGE,headers={
 "User-Agent":UA,"Accept-Encoding":"identity"}),timeout=23) as response:
 body=response.read(500000).decode("utf8","replace")
frames=Frames();frames.feed(body)
selected=[]
for raw in frames.frames:
 u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,raw))
 if u.scheme=="https" and u.hostname=="voe.sx" and re.fullmatch(r"/e/[A-Za-z0-9_-]{6,80}/?",u.path):
  selected.append(u.geturl())
assert len(set(selected))==1,("selected iframe ambiguous",len(selected))
url=selected[0]
assert EDGE.is_file()
folder=tempfile.mkdtemp(prefix="asterion_helloporn_public_voe_")
try:
 proc=subprocess.run([str(EDGE),"--headless=new","--disable-gpu",
  "--no-first-run","--no-default-browser-check","--disable-extensions",
  "--disable-background-networking","--virtual-time-budget=9000",
  "--user-data-dir="+folder,"--dump-dom",url],
  capture_output=True,timeout=34)
 markup=proc.stdout.decode("utf8","replace")
 print(json.dumps({"provider":"HelloPorn","stage":"SELECTED_VOE_EDGE_PUBLIC",
  "edge_exit":proc.returncode,"dom_bytes":len(markup),
  "real_video_elements":len(re.findall(r"<video\b",markup,re.I)),
  "m3u8_literals":len(re.findall(r"\.m3u8",markup,re.I)),
  "mp4_literals":len(re.findall(r"\.mp4",markup,re.I)),
  "external_iframes":len(re.findall(r"<iframe",markup,re.I)),
  "gate":bool(re.search(r"just a moment|verify you are human|cf-turnstile",markup[:20000],re.I)),
  "browser_error":bool(re.search(r"ERR_CONNECTION|This site can.t be reached|ERR_NAME_NOT_RESOLVED",markup,re.I))}),flush=True)
 if proc.returncode!=0 or len(markup)<500:
  # Edge exit=0 with empty stdout is NOT a functioning selected media page.
  # Previous diagnostic silently labelled this as success. Keep an explicit
  # nonzero status so live acceptance cannot pass on a blank renderer.
  print(json.dumps({"provider":"HelloPorn",
   "status":"SELECTED_VOE_RENDER_UNVERIFIED",
   "edge_exit":proc.returncode,"dom_bytes":len(markup)}),flush=True)
  raise SystemExit(3)
finally:shutil.rmtree(folder,ignore_errors=True)
