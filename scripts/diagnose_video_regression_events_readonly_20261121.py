#!/usr/bin/env python3
"""Read-only device diagnostics: selected JAV Guru and JAVideo job stages.
No titles, media paths, selected URLs, cookies or credentials are exported.
Copies SQLite databases and journals to a temporary PC directory only.
"""
import argparse,collections,pathlib,sqlite3,subprocess,tempfile,urllib.parse,json,re
from contextlib import closing
p=argparse.ArgumentParser()
p.add_argument("--device",required=True)
p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
a=p.parse_args()
def read(remote):
 return subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,
   "cat",remote],capture_output=True,timeout=20)
with tempfile.TemporaryDirectory(prefix="adl_regressions_") as tmp:
 root=pathlib.Path(tmp)
 names=("videoflow.sqlite","asterion_download_events_v1.sqlite")
 for name in names:
  for suffix in ("","-wal","-shm"):
   response=read("databases/"+name+suffix)
   if response.returncode==0 and response.stdout:
    (root/(name+suffix)).write_bytes(response.stdout)
 selected=[]
 with closing(sqlite3.connect("file:"+str(root/names[0])+"?mode=ro",uri=True)) as db:
  selected=db.execute("""SELECT id,url,state,progress,attempts,error,updated,request_origin
       FROM videos WHERE url LIKE '%jav.guru%' OR url LIKE '%javideo.net%'
       ORDER BY updated DESC LIMIT 35""").fetchall()
 byid=collections.defaultdict(list)
 events=root/names[1]
 if events.is_file():
  with closing(sqlite3.connect("file:"+str(events)+"?mode=ro",uri=True)) as db:
   for id,url,state,progress,attempts,error,updated,origin in selected:
    for utc,pid,stage,result,amount in db.execute(
        """SELECT timestamp_utc_ms,pid,stage,result_code,verified_bytes
           FROM events WHERE video_id=? ORDER BY seq DESC LIMIT 26""",(id,)):
     byid[id].append({"utc":utc,"pid":pid,"stage":stage,"result":result,"bytes":amount})
 for id,url,state,progress,attempts,error,updated,origin in selected[:15]:
  host=urllib.parse.urlsplit(url).hostname
  receipt=read("files/videoflow-transfer-stages-v2/"+str(id)+".stage")
  stage=receipt.stdout.decode("utf-8","replace").strip()[:135] if receipt.returncode==0 else ""
  # Journal only stores sanitized stages/bytes; error may still contain URL, redact.
  error=re.sub(r"https?://[^\\s'\\\"]+","[URL]",str(error or ""))[:185]
  print(json.dumps({"host":host,"id":id,"state":state,"progress":progress,
     "attempts":attempts,"error":error,"origin":origin,
     "stage_file":stage,"events_newest_first":byid[id]}),flush=True)
