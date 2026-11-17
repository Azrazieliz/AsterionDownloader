#!/usr/bin/env python3
"""Live JAVIDEO exact selected Play -> rendered publisher player contract.

Uses existing local Edge and CDP only. One exact first-party user-selected
article, one normal Play click, observes the active selected player. Never
solves CAPTCHA, intercepts unrelated network traffic or uses ad iframe media.
"""
import json,os,re,socket,subprocess,tempfile,time,urllib.parse,urllib.request,shutil
from pathlib import Path
from websockets.sync.client import connect
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
SELECTED="https://javideo.net/en/fc2ppv-4994603"
HOSTS={"streamtape.com","streamtape.to","streamtape.net","streamtape.site","sbtape.com"}
def freeport():
 with socket.socket() as s:s.bind(("127.0.0.1",0));return s.getsockname()[1]
def request(port):
 with urllib.request.urlopen("http://127.0.0.1:"+str(port)+"/json",timeout=2) as r:return json.load(r)
def evaluate(ws,ident,expr):
 ws.send(json.dumps({"id":ident,"method":"Runtime.evaluate",
   "params":{"expression":expr,"returnByValue":True,"awaitPromise":True}}))
 for _ in range(30):
  reply=json.loads(ws.recv(timeout=7))
  if reply.get("id")!=ident:continue
  return reply.get("result",{}).get("result",{}).get("value")
 raise RuntimeError("CDP response expired")
def main():
 assert EDGE.is_file(),"Existing Edge executable unavailable"
 # Windows Edge may retain locked profile files after taskkill; avoid
 # TempDirectory cleanup overriding real publisher/CDP diagnostic failures.
 if True:
  folder=tempfile.mkdtemp(prefix="asterion_javideo_play_")
  port=freeport()
  browser=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
    "--no-first-run","--no-default-browser-check","--disable-extensions",
    "--disable-background-networking","--mute-audio",
    "--remote-debugging-port="+str(port),"--remote-allow-origins=*",
    "--user-data-dir="+folder,SELECTED],
    stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  try:
   target=None
   for _ in range(65):
    try:
     target=next((x for x in request(port) if x.get("type")=="page" and
       x.get("url","").startswith(SELECTED)),None)
     if target:break
    except Exception:pass
    if browser.poll() is not None:break
    time.sleep(.25)
   assert target,"No current selected publisher browser tab"
   with connect(target["webSocketDebuggerUrl"],open_timeout=7,close_timeout=2,
                max_size=1_000_000) as ws:
    stats=None
    for step in range(12):
     stats=evaluate(ws,10+step,r"""(() => ({
       ready:document.readyState,page:location.href,
       play:!!document.querySelector('#clickfakeplayer'),
       activePlayer:document.querySelectorAll('.embed-responsive.player:not(.d-none)').length,
       visibleGate:[...document.querySelectorAll('.cf-turnstile,.g-recaptcha,.h-captcha')]
        .some(e=>e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0)
     }))()""")
     if isinstance(stats,dict) and stats.get("ready")!="loading":break
     time.sleep(.4)
    assert isinstance(stats,dict),"Current selected player state unavailable"
    p=urllib.parse.urlsplit(stats.get("page",""))
    assert p.hostname=="javideo.net" and p.path=="/en/fc2ppv-4994603",(
     "publisher selected watch unexpectedly navigated",p.hostname,p.path)
    print(json.dumps({"stage":"ACTUAL_SELECTED_BROWSER",
      "play_button":stats.get("play"),"active_player":stats.get("activePlayer"),
      "visible_captcha":stats.get("visibleGate")}),flush=True)
    if stats.get("visibleGate"):
     print(json.dumps({"status":"PUBLISHER_CAPTCHA_REQUIRED_NO_BYPASS"}),flush=True);return 2
    if not stats.get("play"):return 3
    clicked=evaluate(ws,40,r"""(() => {
      const btn=document.querySelector('#clickfakeplayer');
      if(!btn||btn.dataset.asterionClicked)return false;
      btn.dataset.asterionClicked='1';btn.click();return true;
    })()""")
    assert clicked is True,"Selected player Play control could not be activated"
    found={}
    for step in range(14):
     found=evaluate(ws,50+step,r"""(() => ({
       page:location.href,frames:[...document.querySelectorAll(
       '.embed-responsive.player:not(.d-none) iframe[src]')].map(x=>({
         uri:new URL(x.getAttribute('src'),location.href).href,
         ad:!!x.closest('aside,.ads,.related,.recommendations,.sidebar,[data-ad]')
       })).slice(0,12),
       active:document.querySelectorAll('.embed-responsive.player:not(.d-none)').length,
       visibleGate:[...document.querySelectorAll('.cf-turnstile,.g-recaptcha,.h-captcha')]
         .some(e=>e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0)
     }))()""")
     if not isinstance(found,dict):break
     if found.get("visibleGate") or found.get("frames"):break
     time.sleep(.9)
    assert isinstance(found,dict),"selected Play CDP evaluation returned no state"
    frames=found.get("frames",[])
    exact=[]
    for item in frames:
     uri=urllib.parse.urlsplit(item.get("uri",""))
     if (uri.scheme=="https" and uri.hostname in HOSTS and not item.get("ad") and
         re.fullmatch(r"/(?:e|v|embed)/[A-Za-z0-9_-]{5,100}/?",uri.path)):
      exact.append(item["uri"])
    print(json.dumps({"stage":"SELECTED_PLAY_RENDERED",
      "visible_captcha":found.get("visibleGate"),"active_player_containers":found.get("active"),
      "iframe_count":len(frames),"iframe_hosts":sorted(set(urllib.parse.urlsplit(x["uri"]).hostname for x in frames)),
      "exact_selected_streamtape_count":len(set(exact))}),flush=True)
    if found.get("visibleGate"):return 2
    if len(set(exact))==1:
     print(json.dumps({"status":"EXACT_STREAMTAPE_IFRAME_FOUND_NOT_FULL_MEDIA"}),flush=True)
     return 0
    print(json.dumps({"status":"SELECTED_ORIGINAL_IFRAME_UNRESOLVED"}),flush=True)
    return 4
  finally:
   subprocess.run(["taskkill","/PID",str(browser.pid),"/T","/F"],
                  capture_output=True,timeout=12)
   try:browser.wait(timeout=4)
   except Exception:pass
   shutil.rmtree(folder,ignore_errors=True)
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as e:
  print(json.dumps({"status":"SELECTED_BROWSER_PROBE_FAILED",
    "type":type(e).__name__,"reason":str(e)[:180]}),flush=True)
  raise SystemExit(5)
