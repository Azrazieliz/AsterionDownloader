"""Read only diagnostic snapshots from the owner's already-approved Asterion Control
integration. Uses the installed paired client; never prints pairing secrets.
There is no remote shell, cross-app mutation, credential export or media export.
"""
import json
import os
import sys
from pathlib import Path

control_root=Path(os.environ.get("ASTERION_CONTROL_PYTHON_ROOT",r"D:\AsterionControlRelease"))
if not (control_root/"asterion_remote"/"phone_bridge.py").is_file():
    raise SystemExit("Installed approved Control client not found")
sys.path.insert(0,str(control_root))
from asterion_remote.phone_bridge import request

package="com.azrael.pixivdumpsync.acceptance"
for action in ("diagnostics.snapshot","diagnostics.events"):
    item={"package":package,"action":action,
          "request_id":"dl20261117readonly"+action.replace(".","")+"i11",
          "payload":{"limit":18}}
    try:
        response=request("/app-bridge/invoke","POST",item,timeout=10).json()
        print(action, json.dumps(response,ensure_ascii=True)[:19000])
    except Exception as error:
        print(action,"error_class",type(error).__name__)
