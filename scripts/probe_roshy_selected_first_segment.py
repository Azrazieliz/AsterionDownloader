#!/usr/bin/env python3
"""Real selected Roshy 480p HLS media segment byte probe, not full-file claim.

Follows only exact selected Kamehaus player JWPlayer HLS4 and official master
variant link. First real segment is taken from that 480p playlist; no guessed
source, ad, unrelated media, credential bypass or complete-file assertion.
"""
import json,re,urllib.parse,urllib.request,urllib.error
import sys
from pathlib import Path
import verify_roshy_selected_packed_hls as p
def get(url,referer,max_bytes):
 h={"User-Agent":p.UA,"Referer":referer,
    "Accept-Encoding":"identity","Accept":"*/*"}
 req=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(req,timeout=22) as r:
  return r.status,r.headers,r.read(max_bytes),r.url
def main():
 status,_,raw,_=get(p.URL,p.PARENT,250000)
 assert status==200
 scripts=p.Script();scripts.feed(raw.decode("utf-8","replace"))
 decoded=next((p.deobfuscate(s) for s in scripts.raw if
  "eval(function(p,a,c,k,e,d)" in s),"")
 assert decoded and "tt242urr647yem" in decoded
 match=re.search(r"\bvar\s+links\s*=\s*",decoded)
 assert match
 links,_=json.JSONDecoder().raw_decode(decoded[match.end():].lstrip())
 master=urllib.parse.urljoin(p.URL,links["hls4"])
 assert urllib.parse.urlsplit(master).hostname=="kamehaus.net"
 status,headers,raw,_=get(master,p.URL,180000)
 text=raw.decode("utf-8","replace")
 assert status==200 and text.startswith("#EXTM3U")
 lines=text.splitlines()
 variants=[]
 for i,line in enumerate(lines[:-1]):
  if line.startswith("#EXT-X-STREAM-INF"):
   m=re.search(r"RESOLUTION=(\d+)x(\d+)",line)
   if m and lines[i+1].strip() and not lines[i+1].startswith("#"):
    variants.append((int(m.group(2)),urllib.parse.urljoin(master,lines[i+1].strip())))
 assert variants,("Selected HLS has no playable variants",len(lines))
 exact=min(variants,key=lambda x:x[0])
 status,headers,raw,url=get(exact[1],master,250000)
 assert status==200 and raw.lstrip().startswith(b"#EXTM3U")
 playlist=raw.decode("utf-8","replace")
 segment=next((line.strip() for line in playlist.splitlines() if
  line.strip() and not line.startswith("#")),None)
 assert segment
 selected=urllib.parse.urljoin(url,segment)
 u=urllib.parse.urlsplit(selected)
 assert u.scheme=="https" and u.hostname
 print(json.dumps({"stage":"SELECTED_VARIANT_PLAYLIST",
  "variant_height":exact[0],"playlist_bytes":len(raw),
  "segment_count":len([x for x in playlist.splitlines() if x and not x.startswith('#')]),
  "segment_host":u.hostname,"segment_ext":u.path.rsplit('.',1)[-1][:8],
  "encryption_key_tags":playlist.count("#EXT-X-KEY")}),flush=True)
 for referer_kind,referer in (("player",p.URL),("master",master)):
  try:
   code,h,data,_=get(selected,referer,768)
   print(json.dumps({"stage":"EXACT_SELECTED_HLS_SEGMENT",
    "http":code,"referer":referer_kind,
    "mime":h.get("Content-Type","").split(';')[0],
    "bytes_read":len(data),"host":u.hostname,
    "mpegts_sync":bool(data and data[0]==0x47),
    "fmp4_magic":bool(data[4:8] in (b"ftyp",b"styp"))}),flush=True)
   if code==200 and len(data)>188:return 0
  except Exception as e:
   print(json.dumps({"stage":"SELECTED_SEGMENT_FAILED",
    "referer":referer_kind,"error":type(e).__name__,
    "reason":str(e)[:100]}),flush=True)
 return 2
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"status":"FAILED_NOT_COMPLETE",
    "type":type(e).__name__,"reason":str(e)[:180]}),flush=True)
  sys.exit(3)
