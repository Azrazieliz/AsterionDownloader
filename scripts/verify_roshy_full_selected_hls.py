#!/usr/bin/env python3
"""Proof-oriented selected Roshy full HLS original through bundled yt-dlp.

Exact Roshy Beeteam368 -> Kamehaus/e selected id -> P.A.C.K.E.R JWPlayer
first-party HLS4 playlist. Download entire lowest published video format
into disposable PC staging under a bounded process; validate FFprobe duration
against the actual selected HLS duration and video/audio streams; compute
SHA256; do not print signed CDN URLs or claim success on partial fragments.

A PC whole-file success is NOT Android MediaStore acceptance.
"""
import argparse,hashlib,json,os,re,shutil,subprocess,sys,tempfile,time,urllib.parse
from pathlib import Path

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
def resolve():
 import verify_roshy_selected_packed_hls as p
 with p.request(p.URL,p.PARENT) as response:
  assert response.status==200
  html=response.read(250000).decode("utf-8","replace")
 scan=p.Script();scan.feed(html)
 decodes=[p.deobfuscate(s) for s in scan.raw if
  "eval(function(p,a,c,k,e,d)" in s]
 assert len(decodes)==1 and decodes[0] and "tt242urr647yem" in decodes[0]
 script=decodes[0]
 m=re.search(r"\bvar\s+links\s*=\s*",script)
 assert m
 sources,_=json.JSONDecoder().raw_decode(script[m.end():].lstrip())
 url=urllib.parse.urljoin(p.URL,sources.get("hls4",""))
 u=urllib.parse.urlsplit(url)
 assert u.scheme=="https" and u.hostname=="kamehaus.net" and re.fullmatch(
   r"/stream/[A-Za-z0-9_/-]{10,250}/master\.m3u8",u.path)
 with p.request(url,p.URL) as response:
  hls=response.read(200000)
  assert response.status==200 and hls.lstrip().startswith(b"#EXTM3U")
  assert hls.count(b"#EXT-X-STREAM-INF")>=1
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 assert yt_dlp.version.__version__=="2026.10.26"
 headers={"User-Agent":UA,"Referer":p.URL}
 with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,"skip_download":True,
  "noplaylist":True,"socket_timeout":20,"retries":0,
  "http_headers":headers}) as y:
  meta=y.extract_info(url,download=False)
 chosen=min((f for f in meta.get("formats",[]) if f.get("vcodec")!="none"
    and f.get("protocol")=="m3u8_native" and f.get("url") and f.get("height",0)>0),
    key=lambda f:f["height"])
 duration=float(meta.get("duration",0))
 assert 60<duration<24*60*60
 return {"source":url,"referer":p.URL,"format":chosen["format_id"],
  "height":chosen["height"],"duration":duration,
  "estimate":duration*float(chosen.get("tbr") or 2500)*1000/8}

def child(dest):
 data=json.loads(sys.stdin.read(4500))
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 last=[-1]
 def progress(p):
  if p.get("status")=="downloading":
   size=p.get("downloaded_bytes") or 0
   tick=size//(64*1024*1024)
   if tick>last[0]:
    last[0]=tick
    print(json.dumps({"stage":"HLS_FRAGMENTS","downloaded_bytes":size}),flush=True)
 with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,
   "noplaylist":True,"continuedl":True,
   "retries":2,"fragment_retries":2,"socket_timeout":22,
   "concurrent_fragment_downloads":4,
   "format":data["format"],
   "outtmpl":str(Path(dest)/"selected_roshy.%(ext)s"),
   "paths":{"home":str(dest),"temp":str(dest)},
   "http_headers":{"User-Agent":UA,"Referer":data["referer"]},
   "progress_hooks":[progress]}) as y:
  code=y.download([data["source"]])
 return code

def main():
 p=argparse.ArgumentParser()
 p.add_argument("--child",type=str)
 p.add_argument("--max-seconds",type=int,default=420)
 a=p.parse_args()
 if a.child:return child(a.child)
 info=resolve()
 estimate=int(info["estimate"])
 ffprobe=shutil.which("ffprobe")
 assert ffprobe,"ffprobe unavailable for full original verification"
 available=shutil.disk_usage(tempfile.gettempdir()).free
 assert available>estimate+600_000_000,("insufficient disk",available,estimate)
 print(json.dumps({"provider":"roshy","stage":"SELECTED_HLS_480P",
  "published_height":info["height"],"published_duration_seconds":info["duration"],
  "expected_approx_bytes":estimate,
  "source_first_party_host":"kamehaus.net"}),flush=True)
 start=time.monotonic()
 with tempfile.TemporaryDirectory(prefix="asterion_roshy_full_selected_") as d:
  cmd=[sys.executable,str(Path(__file__).resolve()),"--child",d]
  process=subprocess.Popen(cmd,stdin=subprocess.PIPE,stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,text=True)
  try:
   out,err=process.communicate(json.dumps(info),timeout=a.max_seconds)
  except subprocess.TimeoutExpired:
   process.kill()
   out,err=process.communicate()
   print(json.dumps({"provider":"roshy","status":"INCOMPLETE_TIME_LIMIT",
     "elapsed_seconds":round(time.monotonic()-start,1),
     "children_stdout_tail":out[-500:],"removed_staging":True}),flush=True)
   return 2
  for line in out.splitlines()[-6:]:
   if line.startswith('{"stage"'):print(line,flush=True)
  if process.returncode!=0:
   print(json.dumps({"provider":"roshy","status":"FAILED_NOT_COMPLETE",
     "exit_code":process.returncode,"message":err[-230:].splitlines()[-1]
      if err else "selected stream transfer failed","removed_staging":True}),flush=True)
   return 3
  files=[f for f in Path(d).glob("selected_roshy.*") if f.is_file()
    and f.suffix.lower() in (".mp4",".mkv",".webm")]
  assert len(files)==1,("not exactly one playable final file",len(files))
  selected=files[0]
  result=subprocess.run([ffprobe,"-v","error","-of","json",
    "-show_entries","format=duration,format_name:stream=codec_type,codec_name",
    str(selected)],capture_output=True,text=True,timeout=35)
  assert result.returncode==0,result.stderr[-200:]
  probe=json.loads(result.stdout)
  duration=float(probe.get("format",{}).get("duration",0))
  tracks=[x.get("codec_type") for x in probe.get("streams",[])]
  assert "video" in tracks and duration>=info["duration"]-35 and duration<=info["duration"]+35,(
    "selected full duration mismatch",info["duration"],duration,tracks)
  h=hashlib.sha256()
  with selected.open("rb") as stream:
   for block in iter(lambda:stream.read(1024*1024),b""):h.update(block)
  print(json.dumps({"provider":"roshy","status":"COMPLETE_SELECTED_HLS_VIDEO_VERIFIED",
    "bytes":selected.stat().st_size,"duration_seconds":duration,
    "published_duration_seconds":info["duration"],
    "tracks":tracks,"sha256_prefix":h.hexdigest()[:20],
    "elapsed_seconds":round(time.monotonic()-start,1),
    "source_only_pc":True,"file_discarded_after_test":True}),flush=True)
  return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as exc:
  print(json.dumps({"provider":"roshy","status":"FAILED_NOT_COMPLETE",
    "type":type(exc).__name__,"error":str(exc)[:230]}),flush=True)
  sys.exit(4)
