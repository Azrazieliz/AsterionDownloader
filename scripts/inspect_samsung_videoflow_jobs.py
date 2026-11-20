#!/usr/bin/env python3
"""Read-only Samsung AsterionDownloader VideoFlow SQLite inspection.

Use Android run-as with the owner's existing ADB device; NO raw URLs, media,
tokens, cookies, or downloaded files are printed or uploaded.
A temporary local DB + WAL snapshot is deleted at exit. Do not write to app.
"""
import json,os,pathlib,sqlite3,subprocess,tempfile,urllib.parse
PACKAGE="com.azrael.pixivdumpsync.acceptance"
FILES=("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm")
def output(adb,params,timeout=28):
 return subprocess.check_output([adb,*params],timeout=timeout,stderr=subprocess.PIPE)
def safe_text(s,n=95):
 return str(s or "").replace("\r"," ").replace("\n"," ")[:n]
def main():
 adb=os.environ.get("ASTERION_ADB_PATH","adb")
 with tempfile.TemporaryDirectory(prefix="asterion_videoflow_readonly_") as tmp:
  root=pathlib.Path(tmp)
  for name in FILES:
   data=output(adb,["exec-out","run-as",PACKAGE,"cat","databases/"+name])
   if len(data)>20_000_000:raise RuntimeError("unexpected DB snapshot size")
   (root/name).write_bytes(data)
  con=sqlite3.connect((root/"videoflow.sqlite").as_posix())
  try:
   con.execute("pragma query_only=on")
   tables=[r[0] for r in con.execute("select name from sqlite_master where type='table'")]
   columns=[r[1] for r in con.execute("pragma table_info(videos)")]
   print(json.dumps({"stage":"ON_DEVICE_PRIVATE_DB_SCHEMA",
     "table_names":tables,"videos_columns":columns,
     "database_files_exported":False,"read_only":True}),flush=True)
   expected={"id","url","state","error","attempts","progress","updated"}
   missing=sorted(expected.difference(columns))
   if missing:
    print(json.dumps({"stage":"MISSING_FIELDS","names":missing}),flush=True)
    return 3
   extras=[x for x in ("heartbeat_at","lease","retry_after","saved_uri") if x in columns]
   sql="select "+",".join(["id","url","state","error","attempts","progress","updated",*extras])+" from videos order by updated desc limit 100"
   out=[]
   for row in con.execute(sql):
    r=dict(zip(["id","url","state","error","attempts","progress","updated",*extras],row))
    u=urllib.parse.urlsplit(str(r.get("url") or ""))
    host=u.hostname or ""
    if not any(x in host for x in ("jav.guru","javideo.net","helloporn.com","hentaihaven.xxx","mp4hentai.com","uncensoredhentai.xxx","pornhub.com","roshy.tv")):continue
    path=u.path
    source_type=("hentai_episode" if "/episode-" in path else
      "hentai_series" if "/watch/" in path else
      "video_selected" if path.startswith(("/video-","/en/","/watch/")) else
      "publisher_article" if host=="jav.guru" else "other")
    err=safe_text(r["error"],150)
    for host_part in ("https://","http://","voe.sx/","jav.guru/"):
     if host_part in err:err=err.split(host_part)[0]+"[URL_REDACTED]"
    out.append({
      "id":r["id"],"host":host,"path_kind":source_type,
      "path_depth":len([p for p in path.split("/") if p]),
      "path_characters":len(path),"state":r["state"],
      "attempts":r["attempts"],"progress":r["progress"],
      "updated":r["updated"],"error_summary":err,
      **{k:r.get(k) for k in extras if k!="saved_uri"},
      "has_saved_uri":bool(r.get("saved_uri"))
    })
   print(json.dumps({"stage":"ACTUAL_SELECTED_ANDROID_VIDEO_JOBS",
     "matching_count":len(out),"jobs":out[:65],
     "selected_urls_exported":False,"credentials_exported":False}),flush=True)
   return 0
  finally:
   con.close()
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as e:
  print(json.dumps({"stage":"READ_ONLY_DB_DIAG_FAILED",
    "type":type(e).__name__,"error":str(e)[:200]}),flush=True)
  raise SystemExit(3)
