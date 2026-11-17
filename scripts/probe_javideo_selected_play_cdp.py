#!/usr/bin/env python3
"""Reproduce exact selected JAVIDEO Play in existing local Edge (no new browser).

CDP reads a single public /en/<selected> page, clicks its explicit
#clickfakeplayer once, waits for only the selected .embed-responsive.player
iframe and rejects adverts/related players. Never solves CAPTCHA,
intercepts requests, guesses StreamTape URLs, or prints opaque signed links.
"""
import json,os,re,socket,subprocess,tempfile,time,urllib.parse,urllib.request
from pathlib import Path
from websockets.sync.client import connect

EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
PAGE="https://javideo.net/en/fc2ppv-4994603"
ALLOWED={"streamtape.com","streamtape.to","streamtape.net",
         "streamtape.site","sbtape.com"}
def port():
 with socket.socket() as sock:
  sock.bind(("127.0.0.1",0));return sock.getsockname()[1]
def targets(port_number):
 with urllib.request.urlopen("http://127.0.0.1:"+str(port_number)+"/json",
      timeout=2) as response:
  return json.load(response)
def eval_js(ws,seq,script):
 req={"id":seq,"method":"Runtime.evaluate","params":{
  "expression":script,"returnByValue":True,"awaitPromise":True}}
 ws.send(json.dumps(req))
 for _ in range(45):
  msg=json.loads(ws.recv(timeout=6))
  if msg.get("id")!=seq:continue
  if msg.get("error"):return {"error":msg["error"].get("message","")}
  out=msg.get("result",{})
  if out.get("exceptionDetails"):
   return {"error":"Runtime JS exception"}
  return out.get("result",{}).get("value")
 return {"error":"CDP evaluate timeout"}
def main():
 assert Path(EDGE).is_file(),"Existing Edge executable missing"
 try:
  with tempfile.TemporaryDirectory(prefix="asterion_javideo_cdp_") as directory:
   p=port()
   proc=subprocess.Popen([EDGE,"--headless=new","--disable-gpu",
      "--no-first-run","--no-default-browser-check",
      "--disable-extensions","--mute-audio","--disable-background-networking",
      "--remote-debugging-port="+str(p),"--remote-allow-origins=*",
      "--user-data-dir="+directory,PAGE],
      stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
   try:
    target=None
    for _ in range(70):
     try:
      pages=targets(p)
      target=next((x for x in pages if x.get("type")=="page" and
       x.get("url","").startswith(PAGE)),None)
      if target:break
     except Exception:pass
     if proc.poll() is not None:break
     time.sleep(0.2)
    assert target is not None,"Selected page CDP target unavailable"
    with connect(target["webSocketDebuggerUrl"],open_timeout=7,
        close_timeout=2,max_size=1_000_000) as ws:
     initial=None
     for i in range(12):
      initial=eval_js(ws,10+i,r"""(() => ({
        url:location.href,ready:document.readyState,
        playButton:!!document.querySelector('#clickfakeplayer'),
        pageTitle:document.title,
        challenge:/just a moment|verify you are human|turnstile/i.test(
          document.title+' '+(document.body?.innerText||'').slice(0,1500))
      }))()""")
      if isinstance(initial,dict) and initial.get("ready")!="loading":break
      time.sleep(0.3)
     assert isinstance(initial,dict),"Cannot inspect selected publisher DOM"
     uri=urllib.parse.urlsplit(initial.get("url",""))
     assert uri.hostname=="javideo.net" and uri.path.startswith("/en/fc2ppv-4994603"),(
       "Selected page changed to another URL")
     print(json.dumps({"stage":"SELECTED_PAGE_LOADED",
       "play_button":initial.get("playButton"),
       "challenge":initial.get("challenge"),
       "ready":initial.get("ready")}),flush=True)
     if initial.get("challenge"):
      print(json.dumps({"status":"VERIFICATION_REQUIRED_NO_BYPASS"}),flush=True)
      return 2
     if not initial.get("playButton"):
      print(json.dumps({"status":"NO_SELECTED_PLAY_BUTTON"}),flush=True)
      return 3
     clicked=eval_js(ws,40,r"""(() => {
       const b=document.querySelector('#clickfakeplayer');
       if(!b || b.dataset.asterionClicked==='1')return false;
       b.dataset.asterionClicked='1';b.click();return true;
     })()""")
     assert clicked is True,"Selected public Play could not be clicked"
     best=None
     for i in range(18):
      found=eval_js(ws,50+i,r"""(() => ({
        page:location.href,
        frames:[...document.querySelectorAll(
          '.embed-responsive.player:not(.d-none) iframe[src]')].map(x=>({
            src:new URL(x.getAttribute('src'),location.href).href,
            inAds:!!x.closest('aside,.related,.ads,.advertisement,.sidebar')
          })).slice(0,8),
        hiddenPlayers:document.querySelectorAll(
          '.embed-responsive.player.d-none iframe[src]').length,
        playerRoot:document.querySelectorAll('.embed-responsive.player:not(.d-none)').length,
        challenge:/just a moment|verify you are human|turnstile/i.test(
          document.title+' '+(document.body?.innerText||'').slice(0,500))
      }))()""")
      if not isinstance(found,dict):break
      if urllib.parse.urlsplit(found.get("page","")).hostname!="javideo.net":
       return 4
      best=found
      if found.get("frames"):break
      time.sleep(0.8)
     accepted=[]
     for frame in (best or {}).get("frames",[]):
      u=urllib.parse.urlsplit(frame.get("src",""))
      if (u.scheme=="https" and u.hostname in ALLOWED and
       re.fullmatch(r"/(?:e|v|embed)/[a-zA-Z0-9_-]{5,100}/?",u.path)
       and not frame.get("inAds")):
       accepted.append(u.geturl())
     print(json.dumps({"stage":"SELECTED_PLAY_RESULT",
       "visible_player_roots":(best or {}).get("playerRoot"),
       "iframes":len((best or {}).get("frames",[])),
       "verified_selected_streamtape":len(set(accepted)),
       "frame_hosts":sorted(set(urllib.parse.urlsplit(f["src"]).hostname
           for f in (best or {}).get("frames",[]))),
       "challenge":(best or {}).get("challenge")}),flush=True)
     if len(set(accepted))==1:
      print(json.dumps({"status":"SELECTED_STREAMTAPE_FRAME_ATTESTED_NOT_MEDIA",
        "route_host":urllib.parse.urlsplit(accepted[0]).hostname}),flush=True)
      return 0
     print(json.dumps({"status":"SELECTED_MEDIA_UNRESOLVED_NOT_COMPLETE"}),flush=True)
     return 5
   finally:
    subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],
     capture_output=True,timeout=12)
 except Exception as e:
  print(json.dumps({"status":"CDP_PROBE_FAILED_NOT_COMPLETE",
    "error_type":type(e).__name__,"message":str(e)[:180]}),flush=True)
  return 6
if __name__=="__main__":
 import sys
 sys.exit(main())
