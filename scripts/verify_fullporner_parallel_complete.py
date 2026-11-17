#!/usr/bin/env python3
"""Real selected FullPorner full original via bounded parallel verified ranges.

Selected original is obtained from official /watch/<id> -> /player/<id>/<mask>
only. Up to eight disjoint Range readers write to their own offsets; each
validates complete Content-Range, MIME, total size, and overlapping 64 bytes.
The final file must contain every byte, have valid ftyp and pass FFprobe.
A PARTIAL / timeout is explicitly failure. Temporary media is always deleted.
No DRM/access bypass, cookies, reposting or private URL logs.
"""
import argparse,concurrent.futures,hashlib,json,os,shutil,subprocess,sys,tempfile,time
import urllib.parse,urllib.request
from pathlib import Path
from verify_nine_exact_original_full_files import fullporner,req
LIMIT=1_500_000_000
def short_selected_source():
 from verify_fullporner_short_publisher_original import (
    PUBLISHER,Listings,site,inspect)
 listing=Listings()
 listing.feed(site(PUBLISHER))
 candidates=[]
 for raw in listing.pages:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(PUBLISHER,raw))
  if u.scheme=="https" and u.hostname in ("fullporner.com","www.fullporner.com") and (
      __import__("re").fullmatch(r"/watch/[a-fA-F0-9]{24}/?",u.path)):
   if u.geturl() not in candidates:candidates.append(u.geturl())
  if len(candidates)>=12:break
 assert candidates,"No actual official publisher watch links"
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  valid=[x for x in pool.map(inspect,candidates) if "bytes" in x]
 small=sorted((v for v in valid if 1_000_000<v["bytes"]<120_000_000),
     key=lambda a:a["bytes"])
 assert small,("No selected short full original",len(valid))
 x=small[0]
 print(json.dumps({"stage":"ACTUAL_SHORT_SELECTED_WATCH",
   "selected_id":x["selected_id"],"quality":x["quality"],
   "selected_original_bytes":x["bytes"],
   "publisher_watch_path":x["selected"]}),flush=True)
 return {"provider":"fullporner","selected_id":x["selected_id"],
     "quality":x["quality"],"source":x["source"],"referer":x["referer"]}

def main():
 cli=argparse.ArgumentParser()
 cli.add_argument("--workers",type=int,default=8)
 cli.add_argument("--chunk-mb",type=int,default=8)
 cli.add_argument("--max-seconds",type=int,default=430)
 cli.add_argument("--short-published",action="store_true")
 o=cli.parse_args()
 assert o.workers in range(1,13)
 assert o.chunk_mb in range(1,17)
 selected=short_selected_source() if o.short_published else fullporner()
 media=selected["source"];referer=selected["referer"]
 with urllib.request.urlopen(req(media,referer,"bytes=0-255"),timeout=28) as response:
  assert response.status==206
  header=response.headers.get("Content-Range","")
  import re
  match=re.fullmatch(r"bytes 0-255/(\d+)",header)
  assert match,("No selected full-size media contract",header)
  length=int(match.group(1))
  assert 1024*1024<length<LIMIT
  assert response.headers.get("Content-Type","").split(";")[0].lower()=="video/mp4"
  assert response.read(256)[4:8]==b"ftyp"
  publisher_etag=response.headers.get("ETag","").strip()
 assert shutil.disk_usage(tempfile.gettempdir()).free>length+500_000_000
 ffprobe=shutil.which("ffprobe")
 assert ffprobe
 chunk=o.chunk_mb*1024*1024
 ranges=[(start,min(start+chunk,length)-1) for start in range(0,length,chunk)]
 launched=time.monotonic()
 print(json.dumps({"provider":"fullporner","stage":"PARALLEL_REAL_SELECTED_MEDIA",
  "selected_id":selected["selected_id"],"quality":selected["quality"],
  "declared_bytes":length,"segments":len(ranges),"connections":o.workers,
  "source_host":urllib.parse.urlsplit(media).hostname,
  "publisher_etag_supplied":bool(publisher_etag)}),flush=True)
 with tempfile.TemporaryDirectory(prefix="asterion_fp_parallel_") as d:
  file=Path(d)/"exact_selected.mp4"
  # Unverified sparse allocation is NOT a downloaded byte.
  with file.open("wb") as fp:fp.truncate(length)
  accepted={}
  def download(one):
   start,end=one
   requested=max(0,start-64)
   expected=end-requested+1
   for attempt in range(3):
    try:
     with urllib.request.urlopen(req(media,referer,
            "bytes="+str(requested)+"-"+str(end)),timeout=38) as response:
      if response.status!=206:raise RuntimeError("HTTP="+str(response.status))
      received=response.headers.get("Content-Range","")
      match=re.fullmatch(r"bytes (\d+)-(\d+)/(\d+)",received)
      if not match or tuple(map(int,match.groups()))!=(requested,end,length):
       raise RuntimeError("Changed/mismatched Content-Range")
      if response.headers.get("Content-Type","").split(";")[0].lower()!="video/mp4":
       raise RuntimeError("Wrong media MIME")
      received_etag=response.headers.get("ETag","").strip()
      if publisher_etag and received_etag and received_etag!=publisher_etag:
       raise RuntimeError("CDN changed ETag across selected ranges")
      buffer=bytearray()
      while len(buffer)<expected:
       part=response.read(min(128*1024,expected-len(buffer)))
       if not part:break
       buffer.extend(part)
      if len(buffer)!=expected:raise RuntimeError(
        "Incomplete range "+str(start)+" received="+str(len(buffer)))
     overlap=bytes(buffer[:start-requested])
     body=memoryview(buffer)[start-requested:]
     if start==0 and body[4:8].tobytes()!=b"ftyp":
      raise RuntimeError("First media range is not MP4 ftyp")
     with file.open("r+b",buffering=0) as out:
      out.seek(start)
      out.write(body)
     return start,end,len(body),overlap
    except Exception as e:
     if attempt==2:raise RuntimeError("Source segment "+str(start)+
       " failed: "+type(e).__name__+" "+str(e)[:105])
     time.sleep(attempt+1)
  with concurrent.futures.ThreadPoolExecutor(max_workers=o.workers) as pool:
   futures={pool.submit(download,block):block for block in ranges}
   total=0;last_log=0
   try:
    for future in concurrent.futures.as_completed(futures):
     start,end,size,overlap=future.result()
     if size!=end-start+1:raise AssertionError("Unequal segment length")
     if start in accepted:raise AssertionError("Duplicate segment")
     accepted[start]=(end,overlap)
     total+=size
     if total-last_log>=64*1024*1024 or total==length:
      print(json.dumps({"provider":"fullporner","stage":"ACTUAL_PARALLEL_BYTES",
        "validated_bytes":total,"declared_bytes":length,
        "elapsed_seconds":round(time.monotonic()-launched,1)}),flush=True)
      last_log=total
     if time.monotonic()-launched>o.max_seconds:
      raise TimeoutError("Total time budget exceeded before full file: "+
       str(total)+"/"+str(length))
   except BaseException:
    for pending in futures:pending.cancel()
    raise
  assert total==length and len(accepted)==len(ranges)
  with file.open("rb") as stream:
   for start in sorted(accepted):
    end,overlap=accepted[start]
    if overlap:
     stream.seek(start-len(overlap))
     assert stream.read(len(overlap))==overlap,(
      "Overlapping publisher segment bytes changed",start)
  sha=hashlib.sha256()
  with file.open("rb") as inp:
   for b in iter(lambda:inp.read(1024*1024),b""):sha.update(b)
  assert file.stat().st_size==length
  probe=subprocess.run([ffprobe,"-v","error","-of","json",
    "-show_entries","format=duration,format_name:stream=codec_type,codec_name",
    str(file)],capture_output=True,text=True,timeout=35)
  assert probe.returncode==0,probe.stderr[:180]
  summary=json.loads(probe.stdout)
  duration=float(summary.get("format",{}).get("duration",0))
  tracks=[s.get("codec_type") for s in summary.get("streams",[])]
  assert duration>=10 and "video" in tracks
  print(json.dumps({"provider":"fullporner",
   "status":"COMPLETE_SELECTED_MP4_VERIFIED",
   "selected_id":selected["selected_id"],"quality":selected["quality"],
   "received_bytes":total,"declared_bytes":length,
   "segments":len(ranges),"parallel_connections":o.workers,
   "all_range_overlaps_verified":True,"sha256_prefix":sha.hexdigest()[:20],
   "duration_seconds":duration,"tracks":tracks,
   "elapsed_seconds":round(time.monotonic()-launched,1),
   "file_discarded_after_test":True}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as exc:
  print(json.dumps({"provider":"fullporner","status":"FAILED_NOT_COMPLETE",
   "type":type(exc).__name__,"error":str(exc)[:240]}),flush=True)
  sys.exit(1)
