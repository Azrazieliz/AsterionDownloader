#!/usr/bin/env python3
"""Verified complete originals from selected FullPorner and 3DHentaiX pages.

One exact selected first-party publisher link per provider; no generic site
scraping, previews, ads, hoster pages, guessed media, or authentication bypass.
Verify all requested byte ranges, exact complete length, ftyp and FFprobe
duration/video stream. Discard temp media after verification. A failure, 403,
timeout or partial file is NOT successful. Does not touch Android/MediaStore.

Usage: py -3 scripts/verify_nine_exact_original_full_files.py fullporner
       py -3 scripts/verify_nine_exact_original_full_files.py 3dhentaix
"""
import argparse, hashlib, html, json, os, re, shutil, subprocess, sys
import tempfile, time, urllib.parse, urllib.request
from html.parser import HTMLParser
from pathlib import Path

UA=("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 "
    "Chrome/131.0 Mobile Safari/537.36")
SITES={
 "fullporner":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
 "3dhentaix":"https://3dhentaix.com/ntr-compilation/",
}
MAX_BYTES=1_500_000_000
CHUNK_BYTES=8*1024*1024

class Frames(HTMLParser):
 def __init__(self):super().__init__();self.frames=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="iframe" and d.get("src"):self.frames.append(d["src"])

def req(url,ref=None,range_value=None,post=None,origin=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"*/*"}
 if ref:h["Referer"]=ref
 if range_value:h["Range"]=range_value
 if origin:h["Origin"]=origin
 if post:h["Content-Type"]="application/x-www-form-urlencoded"
 return urllib.request.Request(url,headers=h,data=post)

def get_html(url,ref=None):
 with urllib.request.urlopen(req(url,ref),timeout=25) as r:
  assert r.status==200,("selected page",r.status)
  raw=r.read(2_000_000)
  assert len(raw)>3000,("selected HTML too short",len(raw))
  return raw.decode("utf-8","replace")

def fullporner():
 page=SITES["fullporner"]
 root=get_html(page)
 p=Frames();p.feed(root)
 valid=[]
 for raw in p.frames:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(page,html.unescape(raw)))
  if u.scheme=="https" and u.hostname in ("fullporner.com","www.fullporner.com") and re.fullmatch(
   r"/player/([0-9]{1,12})/([0-9]{1,4})/?",u.path):
   valid.append(urllib.parse.urlunsplit(u))
 valid=list(dict.fromkeys(valid))
 assert len(valid)==1,("selected publisher player count",len(valid))
 player=valid[0]
 raw=get_html(player,page)
 uri=urllib.parse.urlsplit(player)
 route=re.fullmatch(r"/player/([0-9]{1,12})/([0-9]{1,4})/?",uri.path)
 assert route
 m=re.search(r"""\bvar\s+id\s*=\s*['"]([0-9]{1,12})['"]\s*\.split\(['"]{2}\)\s*\.reverse\(\)\s*\.join\(['"]{2}\)""",raw,re.I)
 q=re.search(r"""\bvar\s+quality\s*=\s*parseInt\(['"]([0-9]{1,4})['"]\)""",raw,re.I)
 assert m and q, "Selected publisher player has no legitimate encoded ID/quality"
 assert m.group(1)==route.group(1) and q.group(1)==route.group(2), "Publisher player identity mismatch"
 mask=int(q.group(1))
 assert mask in range(1,16)
 # Lowest publisher-advertised quality for shortest whole-file test.
 quality=next((n for n,bit in ((360,1),(480,2),(720,4),(1080,8)) if mask&bit),None)
 assert quality
 link="https://xiaoshenke.net/vid/"+m.group(1)[::-1]+"/"+str(quality)
 return {"provider":"fullporner","selected_id":m.group(1),"quality":quality,
         "source":link,"referer":player}

def three_d():
 page=SITES["3dhentaix"]
 root=get_html(page)
 configs=re.findall(r"""window\.awpSignedPlayback\s*=\s*(\{[^}]{12,1200}\})\s*;""",root,re.S)
 assert len(configs)==1,("unique signed config",len(configs))
 config=json.loads(configs[0])
 selected_id=int(config.get("postId",0))
 nonce=str(config.get("nonce",""))
 assert 0<selected_id<1_000_000_000
 assert re.fullmatch(r"[a-zA-Z0-9_-]{6,64}",nonce)
 articles=set(re.findall(r"""<article\b[^>]*\bid=['"]post-(\d+)['"]""",root,re.I))
 assert articles=={str(selected_id)},("Selected article does not match signed player",sorted(articles))
 payload=urllib.parse.urlencode({"action":"tdx_get_signed_playback_url",
  "post_id":str(selected_id),"nonce":nonce}).encode("ascii")
 request=req("https://3dhentaix.com/wp-admin/admin-ajax.php",
  page,post=payload,origin="https://3dhentaix.com")
 request.add_header("X-Requested-With","XMLHttpRequest")
 with urllib.request.urlopen(request,timeout=25) as r:
  assert r.status==200,("publisher signed AJAX",r.status)
  data=json.loads(r.read(300000))
 assert data.get("success") is True,"Publisher denied signed playback"
 link=str(data.get("data",{}).get("url",""))
 uri=urllib.parse.urlsplit(link)
 assert(uri.scheme=="https" and uri.hostname=="cdn.3dhentaix.com"
    and uri.path=="/index.php" and 8<=len(uri.query)<=2048),(
      "Signed player media host/format mismatch")
 return {"provider":"3dhentaix","selected_id":selected_id,
         "quality":"official_signed","source":link,"referer":page}

def full_verify(case,max_sec:int):
 start=time.monotonic()
 media=case["source"]
 referer=case["referer"]
 with urllib.request.urlopen(req(media,referer,"bytes=0-255"),timeout=30) as r:
  status=r.status;mime=r.headers.get("Content-Type","").split(";")[0].lower()
  c_range=r.headers.get("Content-Range","")
  part=r.read(256)
 assert status==206,(case["provider"],"first Range",status)
 m=re.fullmatch(r"bytes 0-255/(\d+)",c_range)
 assert m,("Missing real total from selected source",c_range[:70])
 expected=int(m.group(1))
 assert 10000<expected<=MAX_BYTES,("unsupported complete media size",expected)
 assert mime=="video/mp4" and part[4:8]==b"ftyp",(
  "Selected source is not MP4",mime,part[:8].hex())
 if shutil.disk_usage(tempfile.gettempdir()).free<expected+450_000_000:
  raise RuntimeError("not enough free disk space to verify entire original")
 ffprobe=shutil.which("ffprobe")
 if not ffprobe:raise RuntimeError("ffprobe missing; cannot claim complete playable original")
 print(json.dumps({"provider":case["provider"],"stage":"publisher_original_verified",
  "selected_id":case["selected_id"],"quality":case["quality"],
  "expected_bytes":expected,"first_range_http":status,
  "source_host":urllib.parse.urlsplit(media).hostname}),flush=True)
 with tempfile.TemporaryDirectory(prefix="asterion_exact_full_"+case["provider"]+"_") as directory:
  path=Path(directory)/"exact_selected_original.mp4"
  hash_value=hashlib.sha256()
  written=0
  with path.open("wb") as out:
   while written<expected:
    if time.monotonic()-start>max_sec:
     print(json.dumps({"provider":case["provider"],"status":"INCOMPLETE_TIME_LIMIT",
      "received_bytes":written,"expected_bytes":expected,
      "elapsed_seconds":int(time.monotonic()-start)}),flush=True)
     return 2
    end=min(expected-1,written+CHUNK_BYTES-1)
    retries=0
    while True:
     try:
      with urllib.request.urlopen(req(media,referer,
             "bytes="+str(written)+"-"+str(end)),timeout=30) as response:
       assert response.status==206,("range HTTP",response.status)
       assert response.headers.get("Content-Type","").split(";")[0].lower()=="video/mp4"
       header=response.headers.get("Content-Range","")
       match=re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)",header)
       assert match and (int(match.group(1)),int(match.group(2)),int(match.group(3)))==(
          written,end,expected),("range changed/mismatched",header[:80])
       body=response.read(end-written+2)
       assert len(body)==end-written+1,("truncated body",len(body),end-written+1)
       if written==0:assert body[4:8]==b"ftyp"
       out.write(body);hash_value.update(body)
       written+=len(body)
      break
     except Exception as ex:
      retries+=1
      if retries>=3:raise RuntimeError("selected original range repeatedly failed at "+
       str(written)+" after "+type(ex).__name__+": "+str(ex)[:90])
      time.sleep(1.5*retries)
    if written%(64*1024*1024)<CHUNK_BYTES or written==expected:
     print(json.dumps({"provider":case["provider"],"stage":"actual_media_bytes",
       "received_bytes":written,"expected_bytes":expected,
       "elapsed_seconds":round(time.monotonic()-start,1)}),flush=True)
  assert written==expected==path.stat().st_size
  media_probe=subprocess.run([ffprobe,"-v","error","-of","json",
    "-show_entries","format=duration,format_name:stream=codec_type,codec_name",
    str(path)],capture_output=True,text=True,timeout=40)
  assert media_probe.returncode==0,("FFprobe",media_probe.stderr[:100])
  info=json.loads(media_probe.stdout)
  duration=float(info.get("format",{}).get("duration",0))
  streams=info.get("streams",[])
  assert duration>=10.0 and any(s.get("codec_type")=="video" for s in streams),(
      "not a playable original",duration)
  print(json.dumps({"provider":case["provider"],"status":"COMPLETE_SELECTED_MP4_VERIFIED",
   "selected_id":case["selected_id"],"quality":case["quality"],
   "received_bytes":written,"declared_bytes":expected,
   "duration_seconds":duration,
   "tracks":[s.get("codec_type") for s in streams],
   "sha256_prefix":hash_value.hexdigest()[:20],
   "elapsed_seconds":round(time.monotonic()-start,1),
   "file_discarded_after_test":True}),flush=True)
  return 0

def main():
 ap=argparse.ArgumentParser()
 ap.add_argument("provider",choices=sorted(SITES))
 ap.add_argument("--max-seconds",type=int,default=390)
 opts=ap.parse_args()
 try:
  case=fullporner() if opts.provider=="fullporner" else three_d()
  return full_verify(case,opts.max_seconds)
 except Exception as exc:
  print(json.dumps({"provider":opts.provider,"status":"FAILED_NOT_COMPLETE",
    "error_class":type(exc).__name__,"reason":str(exc)[:230]}),flush=True)
  return 1
if __name__=="__main__":sys.exit(main())
