#!/usr/bin/env python3
"""Asterion Downloader: non-headless NORMAL Edge PC proof of LiveCamRips.
Starts a fresh disposable browser profile on authorized owner's Windows PC.
Does not replay a CAPTCHA, use an external proxy, alter site cookies, or
fetch unrelated files. Read-only DOM/selected-player validation. No secrets.
"""
import json, os, pathlib, shutil, subprocess, sys, tempfile, time, urllib.request, urllib.parse
import uuid

SELECTED="https://livecamrips.to/video/14759344"
EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
TAG="ASTERION_LCR_PC_NORMAL_EDGE"

def main():
 result={"site":"livecamrips.to","selected_article":SELECTED,
         "complete_selected_original":False,
         "normal_browser_exact_player":False}
 profile=pathlib.Path(tempfile.mkdtemp(prefix="asterion-livecamrips-edge-"))
 proc=None
 try:
  if os.name!="nt" or not pathlib.Path(EDGE).exists():
   raise RuntimeError("OWNER_NORMAL_WINDOWS_EDGE_UNAVAILABLE")
  try:
   import websocket
  except ImportError:
   raise RuntimeError("WEBSOCKET_CLIENT_REQUIRED_FOR_LOCAL_BROWSER_DIAGNOSTICS")
  args=[EDGE,"--user-data-dir="+str(profile),
        "--remote-debugging-port=0","--remote-debugging-address=127.0.0.1",
        "--remote-allow-origins=http://127.0.0.1",
        "--no-first-run","--no-default-browser-check",
        "--disable-extensions","--new-window",SELECTED]
  proc=subprocess.Popen(args,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,
                         stderr=subprocess.DEVNULL,creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0))
  portfile=profile/"DevToolsActivePort"
  until=time.monotonic()+25
  while time.monotonic()<until and not portfile.exists():
   if proc.poll() is not None:
    raise RuntimeError("NORMAL_EDGE_EXITED_BEFORE_START")
   time.sleep(.3)
  if not portfile.exists():
   raise RuntimeError("NORMAL_EDGE_DEBUG_PORT_MISSING")
  port=int(portfile.read_text().splitlines()[0])
  if not 1024<=port<=65535:
   raise RuntimeError("INVALID_LOOPBACK_PORT")
  attempts=0
  latest=None
  while attempts<35:
   attempts+=1
   try:
    req=urllib.request.Request(f"http://127.0.0.1:{port}/json/list",
         headers={"Host":f"127.0.0.1:{port}"})
    with urllib.request.urlopen(req,timeout=4) as response:
     pages=json.load(response)
    targets=[p for p in pages if p.get("type")=="page"
         and urllib.parse.urlsplit(p.get("url","")).hostname in ("livecamrips.to","www.livecamrips.to")
         and p.get("webSocketDebuggerUrl","").startswith("ws://127.0.0.1:")]
    if len(targets)!=1:
     latest={"page_count":len(targets)}
     time.sleep(1.0)
     continue
    url=targets[0]["webSocketDebuggerUrl"]
    connection=websocket.create_connection(url,timeout=5,origin="http://127.0.0.1")
    try:
     connection.send(json.dumps({"id":1,"method":"Runtime.evaluate",
       "params":{"returnByValue":True,"expression":"""(() => {
        const host = location.hostname.toLowerCase(), path=location.pathname;
        const frames=[...document.querySelectorAll('iframe#iframe-video[src]')]
           .filter(f=>!f.closest('aside,.ads,.related,.recommended,[data-ad]'));
        const title = document.title || '';
        const text = (document.body?.innerText||'').slice(0,900);
        const challenge = /just a moment|checking your browser|verify you are human|access denied|403 forbidden|captcha/i.test(title+' '+text);
        return {host,path,title_length:title.length,challenge,frame_count:frames.length,
          frame_hosts:frames.map(x=>{try{return new URL(x.src).hostname}catch(e){return ''}}),
          selected_article_markers:document.querySelectorAll('h1,main h2').length,
          body_length:document.body?.innerText?.length||0};
       })()"""}}))
     response=None
     for _ in range(35):
      event=json.loads(connection.recv())
      if event.get("id")==1:
       response=event
       break
     if not response or "exceptionDetails" in response.get("result",{}):
      raise RuntimeError("NORMAL_EDGE_SELECTED_DOM_UNAVAILABLE")
     data=response["result"]["result"].get("value",{})
     if not isinstance(data,dict):
      raise RuntimeError("NORMAL_EDGE_SELECTED_DOM_INVALID")
     latest=data
     result["publisher_challenge"]=bool(data.get("challenge"))
     result["selected_player_host"]=data.get("frame_hosts",[None])[0] if data.get("frame_count")==1 else None
     result["selected_player_count"]=data.get("frame_count")
     if data.get("host") in ("livecamrips.to","www.livecamrips.to") and
           data.get("path")=="/video/14759344" and
           not data.get("challenge") and data.get("frame_count")==1:
      result["normal_browser_exact_player"]=True
      break
    finally:
     connection.close()
   except Exception as e:
    latest={"probe_error":type(e).__name__}
   time.sleep(1.3)
  result["browser_observation"]=latest
  result["poll_count"]=attempts
  if not result["normal_browser_exact_player"]:
   result["reason"]="NORMAL_EDGE_SITE_CHALLENGE_OR_NO_SELECTED_VIDEO"
 except Exception as exc:
  result["reason"]=str(exc)[:200]
 finally:
  if proc is not None and proc.poll() is None:
   subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],capture_output=True,timeout=12)
  for i in range(3):
   try:
    shutil.rmtree(profile)
    result["temporary_profile_removed"]=True
    break
   except OSError:
    time.sleep(.6)
  print(json.dumps(result,sort_keys=True),flush=True)
 return 0 if result["normal_browser_exact_player"] else 2

if __name__=="__main__":
 sys.exit(main())
