#!/usr/bin/env python3
"""Independent full-file PC proof for the publisher-labelled PornBuz Server 2.
Never substitute a trailer, sidebar ad, player HTML or a mere byte sample."""
import json,re,sys,time,urllib.parse
from pathlib import Path
from bs4 import BeautifulSoup
import pc_full_selected_provider_acceptance_20261122 as core
SELECTED="https://pornbuz.com/video/promotional-pussy-ryan-reid-brazzers-93753/"
record={"provider":"pornbuz","lane":"publisher-selected-server-2","complete_selected_original":False}
start=time.monotonic()
try:
 status,final,html=core.page(SELECTED)
 record["publisher_http"]=status
 if status!=200:raise RuntimeError("SELECTED_PUBLISHER_HTTP_"+str(status))
 soup=BeautifulSoup(html,"html.parser")
 candidates=[]
 for node in soup.select("a[href]"):
  if node.find_parent(["aside"]) or node.find_parent(class_=re.compile("related|recommended|recommendations|ads|advertisement|sidebar",re.I)):continue
  labels=" ".join((node.get_text(" ",strip=True),node.get("title",""),node.get("aria-label","")))
  if not re.search(r"(?i)\bserver\s*2\b",labels):continue
  u=urllib.parse.urljoin(SELECTED,node.get("href",""))
  parts=urllib.parse.urlsplit(u)
  if parts.scheme!="https" or parts.hostname not in ("streamtape.com","streamtape.to","streamtape.net","streamtape.site","sbtape.com") or parts.port not in (None,443) or parts.username or parts.password or parts.fragment:continue
  if re.fullmatch(r"/(?:v|e|embed)/[A-Za-z0-9_-]{6,100}(?:/[A-Za-z0-9_.%-]{3,250})?/?",parts.path):
   candidates.append(u)
 candidates=list(dict.fromkeys(candidates))
 record["publisher_selected_server2_count"]=len(candidates)
 if len(candidates)!=1:raise RuntimeError("NO_UNIQUE_PUBLISHER_ATTESTED_SERVER2")
 record["host"]=urllib.parse.urlsplit(candidates[0]).hostname
 source={"source":candidates[0],"referer":SELECTED,"duration":0,
         "reason":"unique publisher-labelled selected Server 2"}
 record.update(core.full_file(SELECTED,source))
except Exception as error:record["reason"]=str(error)[:250]
record["elapsed_seconds"]=round(time.monotonic()-start,1)
print(json.dumps(record,sort_keys=True),flush=True)
Path("pc-pornbuz-selected-server2-receipt.json").write_text(json.dumps(record,indent=2))
if not record["complete_selected_original"]:sys.exit(2)
