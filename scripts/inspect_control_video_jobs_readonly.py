#!/usr/bin/env python3
"""Read-only, owner-approved VideoFlow queue/events via existing Asterion Control.
Never prints media URLs, titles, cookies or network tokens.
"""
import json,os,sys,uuid
from pathlib import Path
control_root=Path(os.environ.get("ASTERION_CONTROL_PYTHON_ROOT",r"D:\AsterionControlRelease"))
if not (control_root/"asterion_remote"/"phone_bridge.py").is_file():
    raise SystemExit("Installed Control module not found")
sys.path.insert(0,str(control_root))
from asterion_remote.phone_bridge import request
package="com.azrael.pixivdumpsync.acceptance"
for action,payload in (
    ("diagnostics.video_jobs",{"limit":20}),
    ("diagnostics.events",{"limit":20})
):
    item={"package":package,"action":action,
          "request_id":"freeze"+uuid.uuid4().hex,
          "payload":payload}
    try:
        response=request("/app-bridge/invoke","POST",item,timeout=15).json()
        print(json.dumps({"action":action,"response":response},
            ensure_ascii=True,default=str)[:19000],flush=True)
    except Exception as error:
        print(json.dumps({"action":action,"error_class":
            type(error).__name__}),flush=True)
