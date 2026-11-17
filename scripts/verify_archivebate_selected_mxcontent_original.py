#!/usr/bin/env python3
"""Exact publisher selected Archivebate -> Mixdrop -> MDCore.wurl proof.

Verify the selected player's encoded MDCore.referrer matches the selected
archivebate watch, not ads/related posts. Parse wurl from the actual unique
unexecuted P.A.C.K.E.R, verify canonical mxcontent.net MP4 and first media
bytes with HTTP Range. Do not print full signed original URL or cookies.
"""
import json,re,urllib.parse,urllib.request,urllib.error,sys
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_roshy_selected_packed_hls import Script,deobfuscate,UA
WATCH="https://archivebate.com/watch/16458050"
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.urls=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="iframe" and a.get("src"):self.urls.append(a["src"])
def req(url,referer,byte_range=None):
 h={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}
 if byte_range:h["Range"]=byte_range
 return urllib.request.Request(url,headers=h)
def extract(decoded,name):
 m=list(re.finditer(r"\bMDCore\."+re.escape(name)+r"\s*=\s*",decoded))
 if len(m)!=1:return None
 try:
  value,_=json.JSONDecoder().raw_decode(decoded[m[0].end():].lstrip())
  return value if isinstance(value,str) else None
 except Exception:return None
def main():
 with urllib.request.urlopen(req(WATCH,WATCH),timeout=23) as r:
  html=r.read(900000).decode("utf8","replace")
 f=Frames();f.feed(html)
 exact=[]
 for src in f.urls:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(WATCH,src))
  if u.scheme=="https" and u.hostname=="mixdrop.ag" and re.fullmatch(
      r"/e/[A-Za-z0-9_-]{5,64}/?",u.path):
   exact.append(u.geturl())
 assert len(set(exact))==1,"selected iframe not unique"
 frame=exact[0]
 with urllib.request.urlopen(req(frame,WATCH),timeout=24) as r:
  effective=r.url
  body=r.read(500000).decode("utf8","replace")
 a,b=urllib.parse.urlsplit(frame),urllib.parse.urlsplit(effective)
 assert a.path==b.path and b.scheme=="https" and b.hostname in (
  "mxdrop.click","mixdrop.ag")
 parser=Script();parser.feed(body)
 matches=[deobfuscate(script) for script in parser.raw if
  "eval(function(p,a,c,k,e,d)" in script]
 matches=[v for v in matches if v and "MDCore.wurl" in v]
 assert len(matches)==1,"no single exact selected packed video configuration"
 decoded=matches[0]
 reference=extract(decoded,"referrer")
 assert urllib.parse.unquote(reference or "")==WATCH,(
  "selected packed video did not attest exact watch page")
 media=extract(decoded,"wurl")
 vfile=extract(decoded,"vfile")
 assert media and vfile and vfile.endswith(".mp4")
 # Mixdrop's selected MDCore.wurl is protocol-relative on its HTTPS player.
 media=urllib.parse.urljoin(effective,media)
 u=urllib.parse.urlsplit(media)
 assert u.scheme=="https" and u.hostname and re.fullmatch(
  r"[a-z0-9]{6,32}\.mxcontent\.net",u.hostname)
 assert u.path.lower().endswith((".mp4",".m4v"))
 assert not u.username and not u.password
 print(json.dumps({"selected":"watch_16458050",
   "stage":"PUBLISHER_ATTESTED_PACKED_SOURCE",
   "cdn_host":u.hostname,"original_ext":u.path.rsplit(".",1)[-1],
   "source_has_query":bool(u.query),
   "same_named_file":u.path.split("/")[-1]==vfile,
   "publisher_referrer_matches":True}),flush=True)
 with urllib.request.urlopen(req(media,effective,"bytes=0-1023"),timeout=28) as response:
  sample=response.read(1024)
  mime=response.headers.get("Content-Type","").split(";")[0]
  cr=response.headers.get("Content-Range","")
  status=response.status
  length=int(response.headers.get("Content-Length","0") or 0)
  declared=int(cr.rsplit("/",1)[-1]) if "/" in cr and cr.rsplit("/",1)[-1].isdigit() else None
  print(json.dumps({"stage":"REAL_FIRST_ORIGINAL_MP4_BYTES",
    "http":status,"mime":mime,"bytes_read":len(sample),
    "range":bool(re.fullmatch(r"bytes 0-1023/[0-9]+",cr)),
    "total_bytes":declared,
    "ftyp":len(sample)>=8 and sample[4:8]==b"ftyp",
    "declared_chunk_length":length}),flush=True)
  if status==206 and mime=="video/mp4" and sample[4:8]==b"ftyp":
   try:
    with urllib.request.urlopen(req(media,frame,"bytes=0-63"),timeout=18) as alternate:
     alt=alternate.read(64)
     print(json.dumps({"stage":"SELECTED_ORIGINAL_REFERER_CHECK",
       "referer_kind":"publisher_attested_mixdrop_ag",
       "http":alternate.status,"bytes_read":len(alt),
       "mp4_magic":alt[4:8]==b"ftyp"}),flush=True)
   except urllib.error.HTTPError as err:
    print(json.dumps({"stage":"SELECTED_ORIGINAL_REFERER_CHECK",
      "referer_kind":"publisher_attested_mixdrop_ag",
      "http":err.code}),flush=True)
  return 0 if status==206 and mime=="video/mp4" and sample[4:8]==b"ftyp" else 3
if __name__=="__main__":
 try:raise SystemExit(main())
 except urllib.error.HTTPError as e:
  print(json.dumps({"stage":"SELECTED_ORIGINAL_ACCESS_FAILED",
    "http":e.code,"host":urllib.parse.urlsplit(e.url).hostname}),flush=True)
  raise SystemExit(4)
 except Exception as e:
  print(json.dumps({"stage":"SELECTED_ORIGINAL_NOT_VERIFIED",
    "error_type":type(e).__name__,"reason":str(e)[:200]}),flush=True)
  raise SystemExit(5)
