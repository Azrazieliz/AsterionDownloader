#!/usr/bin/env python3
"""Observe ONLY the ordinary publisher-selected NHPlayer first-server network.

Resolves selected UncensoredHentai post -> unique NHPlayer /v/ -> unique
published first-party /player.php?i&s&vid; opens that exact page in an isolated
existing Edge profile, respecting normal JS and first-party referrer. Captures
only safe HTTP status/mime/type/host, not cookies, signatures, bodies, or media.
Does not solve challenges, synthesize tokens, or fetch unrelated iframes.
"""
import html,json,re,shutil,socket,subprocess,sys,tempfile,time,urllib.parse,urllib.request
from pathlib import Path
from cdp_stdlib_socket import connect
from inspect_uncensored_nhplayer_ajax_mode import H,get,OWNER
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
def selected():
 d=H();d.feed(get(OWNER,OWNER))
 frames=set()
 for x in d.frames:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(OWNER,html.unescape(x)))
  if(u.scheme=="https" and u.hostname=="nhplayer.com" and
      re.fullmatch(r"/v/[A-Za-z0-9]{8,40}/?",u.path)):frames.add(u.geturl())
 if len(frames)!=1:raise RuntimeError("exact selected iframe ambiguous")
 frame=next(iter(frames))
 s=H();s.feed(get(frame,OWNER))
 if len(s.servers)!=1:raise RuntimeError("selected first-party server ambiguous")
 url=urllib.parse.urljoin(frame,html.unescape(s.servers[0]))
 p=urllib.parse.urlsplit(url)
 if(p.scheme!="https" or p.hostname!="nhplayer.com" or p.path!="/player.php" or
    set(urllib.parse.parse_qs(p.query))!={"i","s","vid"}):
  raise RuntimeError("first-party selected server route invalid")
 return frame,url
def localport():
 with socket.socket() as s:s.bind(("127.0.0.1",0));return s.getsockname()[1]
def targets(port):
 with urllib.request.urlopen(f"http://127.0.0.1:{port}/json",timeout=3) as r:
  return json.load(r)
def main():
 if not EDGE.is_file():raise RuntimeError("Existing Edge not installed")
 ref,url=selected()
 port=localport();profile=tempfile.mkdtemp(prefix="asterion_nh_observer_")
 proc=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
   "--no-first-run","--no-default-browser-check","--disable-extensions",
   "--disable-background-networking","--mute-audio",
   "--remote-debugging-port="+str(port),"--remote-allow-origins=*",
   "--user-data-dir="+profile,"about:blank"],
   stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 try:
  tab=None
  for i in range(45):
   try:
    tab=next((x for x in targets(port) if x.get("type")=="page" and
      x.get("webSocketDebuggerUrl")),None)
    if tab:break
   except Exception:pass
   if proc.poll() is not None:break
   time.sleep(.25)
  if not tab:raise RuntimeError("Local browser tab unavailable")
  with connect(tab["webSocketDebuggerUrl"],open_timeout=7,
      close_timeout=2,max_size=1_000_000) as ws:
   events=[];failures=[]
   def command(i,method,params=None):
    ws.send(json.dumps({"id":i,"method":method,"params":params or {}}))
    for _ in range(220):
     event=json.loads(ws.recv(timeout=8))
     name=event.get("method")
     data=event.get("params") or {}
     if name=="Network.responseReceived":
      response=data.get("response") or {}
      src=urllib.parse.urlsplit(response.get("url") or "")
      if src.hostname in ("nhplayer.com","cdn.htstreaming.com") and
         len(events)<1000:
       events.append({"host":src.hostname,
         "first_route":src.path.strip("/").split("/")[0][:30],
         "method":data.get("type"),
         "http":int(response.get("status") or 0),
         "mime":str(response.get("mimeType") or "")[:65],
         "query_keys":sorted(urllib.parse.parse_qs(src.query))[:7]})
     elif name=="Network.loadingFailed":
      if len(failures)<100:
       failures.append({"error":str(data.get("errorText") or "")[:75],
         "cancelled":bool(data.get("canceled"))})
     if event.get("id")==i:return event.get("result")
    raise TimeoutError("Local browser CDP command timeout")
   command(10,"Network.enable")
   command(11,"Page.enable")
   command(12,"Page.navigate",{"url":url,"referrer":ref})
   state={}
   for i in range(38):
    result=command(101+i,"Runtime.evaluate",{
      "expression":r"""(() => {
         const video=[...document.querySelectorAll('video')].map(v=>({
          ready:v.readyState, duration:Number.isFinite(v.duration)?v.duration:null,
          hasSrc:!!(v.currentSrc||v.src||v.querySelector('source[src]')?.src)
         }));
         const direct=typeof window.videoReady==='string'?window.videoReady:'';
         const u=(()=>{try{return new URL(direct)}catch{return null}})();
         return {ready:document.readyState,host:location.hostname,
          video,playerGlobal:typeof window.videoReady,
          globalPlayable:!!(u&&u.protocol==='https:'),globalHost:u?.hostname||null,
          visibleGate:!!document.querySelector('.cf-turnstile,.g-recaptcha,.h-captcha')};
       })()""",
      "returnByValue":True,"awaitPromise":True})
    state=((result or {}).get("result") or {}).get("value") or {}
    if state.get("globalPlayable") or any(x.get("hasSrc") for x in state.get("video") or []):
     break
    time.sleep(.65)
   by={}
   for x in events:
    key=(x["host"],x["first_route"],x["http"],x["mime"],x.get("method"))
    by[key]=by.get(key,0)+1
   print(json.dumps({"provider":"uncensoredhentai",
     "stage":"EXACT_SELECTED_NHPLAYER_NORMAL_CDP_NETWORK",
     "selected_first_party":state.get("host")=="nhplayer.com",
     "document_ready":state.get("ready"),
     "video_elements":state.get("video"),
     "publisher_js_global":state.get("playerGlobal"),
     "authorized_selected_media_published":state.get("globalPlayable"),
     "selected_js_media_host":state.get("globalHost"),
     "visible_gate":state.get("visibleGate"),
     "first_party_response_shapes":[{
        "host":k[0],"first_route":k[1],"http":k[2],
        "mime":k[3],"resource_type":k[4],"count":v}
        for k,v in list(by.items())[:35]],
     "network_failure_count":len(failures),
     "network_failure_shapes":failures[:12],
     "complete_original_verified":False}),flush=True)
   return 0
 finally:
  subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],
      capture_output=True,timeout=12)
  try:proc.wait(timeout=4)
  except Exception:pass
  shutil.rmtree(profile,ignore_errors=True)
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"uncensoredhentai",
   "status":"SELECTED_CDP_NETWORK_NOT_VERIFIED",
   "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
