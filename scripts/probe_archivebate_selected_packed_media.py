#!/usr/bin/env python3
"""Extract real selected Archivebate Mixdrop packed JWPlayer source as TEXT.

First verify exact publisher watch iframe (no related media). Only the actual
unredirected /e/<id> selected player (canonical same /e/<id> allowed). Unpack
its public source scripts as TEXT; never execute JS, bypass challenges or
guess a CDN. Print media host/type and first bytes, not signed media URLs.
"""
import json,re,urllib.parse,urllib.request,urllib.error,sys
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_roshy_selected_packed_hls import deobfuscate,Script,UA
WATCH="https://archivebate.com/watch/16458050"
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.frames=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="iframe" and a.get("src"):self.frames.append(a["src"])
def get(url,referer,range_header=None):
 headers={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}
 if range_header:headers["Range"]=range_header
 q=urllib.request.Request(url,headers=headers)
 return urllib.request.urlopen(q,timeout=25)
def main():
 with get(WATCH,WATCH) as response:
  page=response.read(1100000).decode("utf-8","replace")
 f=Frames();f.feed(page)
 chosen=[]
 for src in f.frames:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(WATCH,src))
  if u.scheme=="https" and u.hostname=="mixdrop.ag" and re.fullmatch(
       r"/e/[A-Za-z0-9_-]{5,64}/?",u.path):
   chosen.append(u.geturl())
 assert len(set(chosen))==1,"Selected player ambiguous"
 original=chosen[0]
 with get(original,WATCH) as res:
  redirected=res.url
  text=res.read(300000).decode("utf-8","replace")
 a,b=urllib.parse.urlsplit(original),urllib.parse.urlsplit(redirected)
 assert a.path==b.path and b.hostname in ("mixdrop.ag","mxdrop.click")
 scan=Script();scan.feed(text)
 packed=[s for s in scan.raw if "eval(function(p,a,c,k,e,d)" in s]
 print(json.dumps({"selected_iframe":True,"redirected_canonical_host":b.hostname,
  "packed_scripts":len(packed),"inline_scripts":len(scan.raw)}),flush=True)
 found=[]
 for i,script in enumerate(packed):
  dec=deobfuscate(script)
  keys={key:len(re.findall(key,dec,re.I)) for key in (
    r"freefile",r"wurl",r"video",r"mp4",r"source",r"MDCore",r"poster")}
  literals=re.findall(r"""(?:https?:)?//[A-Za-z0-9.-]+/[^\s'"<>\\]{4,450}""",
                     dec,re.I)
  for raw in literals:
   u=urllib.parse.urlsplit(raw if raw.startswith("http") else "https:"+raw)
   if u.scheme!="https" or not u.hostname:continue
   if u.hostname=="cdn.freefile.io" and re.search(r"\.(mp4|m4v)(?:$|\?)",u.path,re.I):
    found.append(u.geturl())
  print(json.dumps({"packed_script":i,"decoded_bytes":len(dec),
    "evidence_keys":keys,
    "candidate_media_host_count":len(set(found))}),flush=True)
 unique=list(dict.fromkeys(found))
 if len(unique)!=1:
  print(json.dumps({"status":"SELECTED_CDN_NOT_UNAMBIGUOUS",
    "count":len(unique)}),flush=True)
  return 2
 media=unique[0]
 with get(media,redirected,"bytes=0-511") as res:
  sample=res.read(512)
  content_range=res.headers.get("Content-Range","")
  mime=res.headers.get("Content-Type","").split(";")[0]
  print(json.dumps({"stage":"SELECTED_EXACT_MEDIA_BYTES",
    "http":res.status,"mime":mime,
    "range_format":bool(re.fullmatch(r"bytes 0-511/[0-9]+",content_range)),
    "sample_size":len(sample),
    "mp4_ftyp":sample[4:8]==b"ftyp",
    "published_host":urllib.parse.urlsplit(media).hostname}),flush=True)
  return 0 if res.status==206 and sample[4:8]==b"ftyp" else 3
if __name__=="__main__":
 try:raise SystemExit(main())
 except urllib.error.HTTPError as err:
  print(json.dumps({"status":"PUBLIC_SOURCE_DENIED",
    "http":err.code,"host":urllib.parse.urlsplit(err.url).hostname}),flush=True)
  raise SystemExit(4)
 except Exception as err:
  print(json.dumps({"status":"NO_VERIFIED_SELECTED_MEDIA",
    "kind":type(err).__name__,"reason":str(err)[:160]}),flush=True)
  raise SystemExit(5)
