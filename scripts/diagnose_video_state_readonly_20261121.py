#!/usr/bin/env python3
"""Read-only per-provider selected-video state from installed release.
Only aggregate hosts, short sanitized error types, and counts leave the phone.
No media, URLs, credentials, titles, or app databases are exported.
"""
import argparse,collections,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse
p=argparse.ArgumentParser();p.add_argument("--device",required=True)
p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
opts=p.parse_args()
with tempfile.TemporaryDirectory(prefix="asterion_video_agg_") as tmp:
 root=pathlib.Path(tmp)
 for name in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  r=subprocess.run(["adb","-s",opts.device,"exec-out","run-as",opts.package,"cat","databases/"+name],
    capture_output=True,timeout=20)
  if r.returncode==0 and r.stdout: (root/name).write_bytes(r.stdout)
 if not (root/"videoflow.sqlite").is_file():
  print("VIDEO_DATABASE_UNREADABLE");raise SystemExit(1)
 with sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True) as db:
  rows=db.execute("""SELECT url,state,progress,error,attempts,updated,heartbeat_at,request_origin
                 FROM videos ORDER BY updated DESC LIMIT 1800""").fetchall()
  by=collections.Counter();errors=collections.Counter();recent=collections.defaultdict(list)
  for url,state,progress,error,attempts,updated,heartbeat,origin in rows:
   try:host=(urllib.parse.urlsplit(url).hostname or "").lower().removeprefix("www.")
   except Exception:host="unparseable"
   if not host or "." not in host:host="unknown"
   by[host,state]+=1
   if error:
    msg=str(error)
    msg=re.sub(r"https?://[^\\s'\\\"]+","[URL]",msg)
    msg=re.sub(r"(?i)(?:token|cookie|authorization|password|apikey|signature)\\s*[=:]\\s*[^\\s,;]+","[REDACTED]",msg)
    msg=msg[:115]
    errors[host,msg]+=1
   if state not in ("complete","saved") and len(recent[host])<4:
    recent[host].append((state,int(progress),int(attempts),origin,
       "stale" if state=="running" and (not heartbeat or int(updated)-int(heartbeat)>90000) else ""))
  print("VIDEO_STATE_COUNTS")
  for (host,state),n in by.most_common():
   print(host,state,n)
  print("VIDEO_FAILURE_KINDS")
  for (host,error),n in errors.most_common(90):print(host,n,error)
  print("SELECTED_PENDING_SAMPLES")
  for host,values in recent.items():print(host,values)
