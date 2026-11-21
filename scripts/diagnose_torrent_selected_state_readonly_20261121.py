#!/usr/bin/env python3
"""Read-only installed torrent state (no magnet URLs, file paths or titles).
Print safety hold, per-job selected counts and sanitized transfer status.
"""
import argparse,subprocess,xml.etree.ElementTree as ET,json,re
p=argparse.ArgumentParser()
p.add_argument("--device",required=True)
p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
a=p.parse_args()
def read(path):
 r=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,
   "cat",path],capture_output=True,timeout=20)
 return r.stdout.decode("utf-8","replace") if r.returncode==0 else ""
def load(name):
 raw=read("shared_prefs/"+name+".xml")
 if not raw:return {}
 doc=ET.fromstring(raw)
 return {x.get("name",""):(x.get("value") if x.get("value") is not None else x.text or "")
   for x in doc if x.get("name")}
prefs=load("torrent_transfers")
safety=load("torrent_native_safety_v1")
jobs=sorted(k[4:] for k in prefs if re.fullmatch(r"job_[a-f0-9]{24}",k))
print(json.dumps({"selected_job_count":len(jobs),"safety":{
 key:safety.get(key) for key in
 ("suspected_device_restart","native_request_running","last_boot_epoch_ms")}
}),flush=True)
for id in jobs[:35]:
 v=str(prefs.get("job_"+id,""))
 stage=read("files/torrent-jobs/"+id+".status").strip()
 selected=str(prefs.get("job_indices_"+id,""))
 titleSafe=re.sub(r"[\w./\\-]{20,}","[name]",stage)
 print(json.dumps({"job":id[:8],"selected_file_count":len(
   [x for x in selected.split(",") if x.isdigit()]),
   "paused":prefs.get("job_paused_"+id),
   "cancelled":prefs.get("job_cancelled_"+id),
   "preference_status":re.sub(r"^[^·]{0,80}·\\s*","",v)[:140],
   "durable_status":titleSafe[:165],
   "status_file_present":bool(stage)}),flush=True)
