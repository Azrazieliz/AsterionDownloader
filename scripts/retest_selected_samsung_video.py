#!/usr/bin/env python3
"""Run one existing owner-selected Android VideoFlow job using actual Share UI.

Only allowed exact VideoDb IDs and HTTPS publisher hosts; no DB mutation,
direct service bypass, fabricated URLs or media/token export. Prints sanitized
state/attempt counters before and after Android's normal selected-video flow.
"""
import json,pathlib,shlex,sqlite3,subprocess,sys,tempfile,time,urllib.parse
from inspect_samsung_videoflow_jobs import PACKAGE,FILES,output
EXPECTED={87:"jav.guru",88:"javideo.net",93:"jav.guru",
          80:"hentaihaven.xxx",90:"helloporn.com",
          22:"uncensoredhentai.xxx",94:"mp4hentai.com"}
def snapshot(video_id):
 with tempfile.TemporaryDirectory(prefix="asterion_selected_job_probe_") as tmp:
  root=pathlib.Path(tmp)
  for name in FILES:
   raw=output("adb",["exec-out","run-as",PACKAGE,"cat","databases/"+name])
   (root/name).write_bytes(raw)
  connection=sqlite3.connect((root/"videoflow.sqlite").as_posix())
  try:
   row=connection.execute(
    "select url,state,attempts,progress,updated,error from videos where id=?",
    (video_id,)).fetchone()
   if not row:raise RuntimeError("selected job not in app database")
   url,state,attempts,progress,updated,error=row
   return {"url":url,"state":state,"attempts":attempts,
    "progress":progress,"updated":updated,
    "error_type":("none" if not error else
      "auth" if "403" in error or "401" in error else
      "unsupported" if "Unsupported" in error else
      "dns" if "network route" in error else
      "other")}
  finally:connection.close()
def public_state(record):
 return {k:v for k,v in record.items() if k!="url"}
def main():
 if len(sys.argv)!=2:raise RuntimeError("require exact approved job ID")
 ident=int(sys.argv[1])
 if ident not in EXPECTED:raise RuntimeError("job ID outside approved selected set")
 before=snapshot(ident)
 parsed=urllib.parse.urlsplit(before["url"])
 if parsed.scheme!="https" or parsed.hostname not in (EXPECTED[ident],"www."+EXPECTED[ident]) or parsed.username or parsed.password:
  raise RuntimeError("job publisher identity invalid")
 if before["state"] in ("running","done"):
  raise RuntimeError("refusing to replay active or completed job")
 if len(before["url"])>1600 or any(c.isspace() for c in before["url"]):
  raise RuntimeError("invalid source URL length or whitespace")
 # The exported AsterionHomeActivity routes user shares into VideoDb.addVideo,
 # and AsterionHomeActivity.onNewIntent handles an existing foreground task.
 # No raw URL is ever printed or entered into command-line telemetry.
 command=(
  "am start -n "+PACKAGE+"/com.azrael.pixivdumpsync.AsterionHomeActivity"
  " -a android.intent.action.SEND -t text/plain"
  " --es android.intent.extra.TEXT "+shlex.quote(before["url"]))
 result=subprocess.run(["adb","shell",command],capture_output=True,text=True,timeout=25)
 if result.returncode!=0:
  raise RuntimeError("Android selected SHARE intake refused")
 print(json.dumps({"stage":"SELECTED_ANDROID_RETEST_SUBMITTED",
   "id":ident,"publisher":parsed.hostname,"before":public_state(before),
   "am_start_exit":result.returncode,"url_exported":False}),flush=True)
 time.sleep(12)
 after=snapshot(ident)
 print(json.dumps({"stage":"SELECTED_ANDROID_RETEST_OBSERVED",
   "id":ident,"after":public_state(after),
   "attempt_newer":after["updated"]>before["updated"],
   "running_or_completed":after["state"] in ("running","done"),
   "original_verified":after["state"]=="done",
   "url_exported":False}),flush=True)
if __name__=="__main__":
 try:main()
 except Exception as error:
  print(json.dumps({"status":"ANDROID_SELECTED_TEST_NOT_VERIFIED",
   "type":type(error).__name__,"description":str(error)[:140]}),flush=True)
  raise SystemExit(3)
