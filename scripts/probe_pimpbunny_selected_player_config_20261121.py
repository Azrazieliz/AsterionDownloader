#!/usr/bin/env python3
"""Read the existing user-selected PimpBunny first-party HTML player config.
No media bytes, signed original URLs, titles, or credentials are exported.
"""
import argparse,json,re,pathlib,tempfile,sqlite3,subprocess,urllib.parse,urllib.request,urllib.error
from contextlib import closing
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_pimpbunny_player_") as d:
 root=pathlib.Path(d)
 for name in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  r=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+name],
     capture_output=True,timeout=20)
  if r.returncode==0 and r.stdout:(root/name).write_bytes(r.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos ORDER BY updated DESC LIMIT 1500").fetchall()
 selected=next((v[0] for v in rows if
   (urllib.parse.urlsplit(v[0]).hostname or "").removeprefix("www.")=="pimpbunny.com"
   and urllib.parse.urlsplit(v[0]).path.startswith("/videos/")),None)
 if not selected:raise RuntimeError("No selected PimpBunny article in installed queue")
 req=urllib.request.Request(selected,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml","Accept-Language":"en-US,en;q=0.8"})
 try:
  with urllib.request.urlopen(req,timeout=18) as r:
   page=r.read(950000).decode("utf-8","replace");http=r.status
 except urllib.error.HTTPError as e:
  page=e.read(70000).decode("utf-8","replace");http=e.code
 scripts=re.findall(r"""(?is)<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>""",page)
 out=[]
 original=None
 for s in scripts:
  if not re.search(r"(?i)\bvideo_url\b|flashvars|kt_player",s):continue
  keyed=[k for k in ("video_url","video_alt_url","video_url_text","video_id","video_duration",
                    "flashvars","kt_player","sources","playlist","format","video_url_1080p",
                    "video_url_720p","video_url_480p") if re.search(r"\b"+k+r"\b",s,re.I)]
  sources=[]
  for key in ("video_url","video_alt_url","video_url_1080p","video_url_720p","video_url_480p"):
   m=re.search(r"""(?i)(?:["']?)"""+key+r"""(?:["']?)\s*[:=]\s*["']([^"'<>\s]{10,2200})["']""",s)
   if not m:continue
   url=m.group(1).replace("\\/","/")
   u=urllib.parse.urlsplit(urllib.parse.urljoin(selected,url))
   if (key=="video_url" and original is None and
       u.scheme=="https" and u.hostname in ("pimpbunny.com","www.pimpbunny.com") and
       u.path.startswith("/get_file/") and ".mp4" in u.path.lower()):
     original=u.geturl()
   sources.append({"key":key,"host":u.hostname,
      "route":u.path.split("/")[1] if len(u.path.split("/"))>1 else "",
      "path_depth":len(u.path.split("/")),"ext":u.path.rsplit(".",1)[-1][:9] if "." in u.path else "",
      "query":bool(u.query)})
  out.append({"chars":len(s),"keys":keyed,"originals":sources,
     "mentions_preview":bool(re.search("preview",s,re.I)),
     "runtime_attribute":bool(re.search(r"video_duration\s*[:=]",s,re.I))})
 print(json.dumps({"host":"pimpbunny.com","http":http,"selected_path_kind":"video" if "/video/" in urllib.parse.urlsplit(selected).path else "other",
   "candidate_scripts":out[:6],"html_get_file_count":page.count("/get_file/"),
   "preview_mp4_count":len(re.findall(r"(?i)preview(?:_[a-z0-9]+)?\.mp4",page)),
   "actual_video_player_id":bool(re.search(r"""(?i)id=["'](?:kt_player|video-player)["']""",page))}),flush=True)
 if original:
  try:
   req=urllib.request.Request(original,headers={
    "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
    "Referer":selected,"Accept":"video/mp4,*/*;q=0.8",
    "Accept-Encoding":"identity","Range":"bytes=0-4095"})
   with urllib.request.urlopen(req,timeout=20) as resp:
    first=resp.read(4096)
    print(json.dumps({"stage":"SELECTED_KVS_FIRST_ORIGINAL_BYTES",
      "http":resp.status,
      "final_host":urllib.parse.urlsplit(resp.url).hostname,
      "mime":resp.headers.get("Content-Type","").split(";")[0],
      "sample_bytes":len(first),
      "mp4_ftyp":b"ftyp" in first[:36],
      "content_range":bool(resp.headers.get("Content-Range")),
      "full_file_verified":False}),flush=True)
  except urllib.error.HTTPError as error:
   print(json.dumps({"stage":"SELECTED_KVS_ORIGINAL_TRANSFER_GATE","http":error.code}),flush=True)
  except Exception as error:
   print(json.dumps({"stage":"SELECTED_KVS_ORIGINAL_TRANSFER_ERROR",
     "error":type(error).__name__}),flush=True)
