#!/usr/bin/env python3
"""Live JAVIDEO exact selected Play -> rendered publisher player contract.

Uses existing local Edge and CDP only. One exact first-party user-selected
article, one normal Play click, observes the active selected player. Never
solves CAPTCHA, intercepts unrelated network traffic or uses ad iframe media.
"""
import json,os,re,socket,subprocess,tempfile,time,urllib.parse,urllib.request,shutil,contextlib,base64
from pathlib import Path
try:
    from websockets.sync.client import connect
except ImportError:
    from cdp_stdlib_socket import connect
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
SELECTED="https://javideo.net/en/fc2ppv-4994603"
HOSTS={"streamtape.com","streamtape.to","streamtape.net","streamtape.site","sbtape.com"}
def freeport():
 with socket.socket() as s:s.bind(("127.0.0.1",0));return s.getsockname()[1]
def request(port):
 with urllib.request.urlopen("http://127.0.0.1:"+str(port)+"/json",timeout=2) as r:return json.load(r)
SELECTED_NETWORK=[]
SELECTED_API_REQUESTS=[]
def selected_network(response,request_id=None):
 url=response.get("url","")
 u=urllib.parse.urlsplit(url)
 host=u.hostname or ""
 if not (host=="play.javideo.net" or host.endswith(".vid-snap-player.com")):
  return
 path=u.path.split("/")
 SELECTED_NETWORK.append({
  "host":host,
  "first_route_segment":path[1][:36] if len(path)>1 else "",
  "second_route_kind":(
     path[2] if len(path)>2 and path[2] in
       {"time","token","stream","video","source","config","player",
        "auth","play","media","manifest","status","embed"} else "{opaque}"),
  "path_extension":u.path.rsplit(".",1)[-1][-10:] if "." in u.path else "",
  "http_status":response.get("status"),
  "mime":response.get("mimeType","")[:70],
  "resource_type":response.get("resourceType"),
  "has_query":bool(u.query)
 })
 if len(SELECTED_NETWORK)>450:SELECTED_NETWORK.pop(0)
 if(host=="play.javideo.net" and u.path.startswith("/api") and
    "json" in (response.get("mimeType") or "").lower() and request_id):
  SELECTED_API_REQUESTS.append(request_id)
def evaluate(ws,ident,expr):
 ws.send(json.dumps({"id":ident,"method":"Runtime.evaluate",
   "params":{"expression":expr,"returnByValue":True,"awaitPromise":True}}))
 deadline=time.monotonic()+18
 # Network.enable before Play is necessary, but first-party page resources
 # can emit hundreds of CDP events before Runtime.evaluate replies.
 # Do not misclassify that traffic as a publisher player failure.
 while time.monotonic()<deadline:
  try:
   reply=json.loads(ws.recv(timeout=min(5,max(.1,deadline-time.monotonic()))))
  except TimeoutError:
   continue
  if reply.get("method")=="Network.responseReceived":
   selected_network(reply.get("params",{}).get("response",{}),reply.get("params",{}).get("requestId"))
  if reply.get("id")!=ident:continue
  return reply.get("result",{}).get("result",{}).get("value")
 raise RuntimeError("CDP selected player evaluation timed out after 18s")
def trusted_selected_play(ws,base_id=930):
 """Send normal Chromium mouse input ONLY to selected JAVIDEO Play widget."""
 location=evaluate(ws,base_id,r"""(() => {
   const chosen=document.querySelector(
      '.embed-responsive.player:not(.d-none) iframe[src]');
   const btn=document.querySelector('#clickfakeplayer');
   if(chosen||!btn||btn.closest(
     'aside,.ads,.advertisement,.related,.recommendations,.sidebar,[data-ad]'))
     return null;
   btn.scrollIntoView({block:'center',inline:'center'});
   const rect=btn.getBoundingClientRect();
   const x=Math.round(rect.left+rect.width/2);
   const y=Math.round(rect.top+rect.height/2);
   if(rect.width<5||rect.height<5||x<0||y<0||
      x>=innerWidth||y>=innerHeight)return null;
   return {x,y,host:location.hostname};
 })()""")
 if not isinstance(location,dict) or location.get("host")!="javideo.net":
  return False
 x,y=location["x"],location["y"]
 for index,(typ,extra) in enumerate((
    ("mouseMoved",{}),
    ("mousePressed",{"button":"left","clickCount":1}),
    ("mouseReleased",{"button":"left","clickCount":1}))):
  ident=base_id+1+index
  ws.send(json.dumps({"id":ident,"method":"Input.dispatchMouseEvent",
    "params":{"type":typ,"x":x,"y":y,**extra}}))
  until=time.monotonic()+9
  while time.monotonic()<until:
   resp=json.loads(ws.recv(timeout=min(5,max(.1,until-time.monotonic()))))
   if resp.get("method")=="Network.responseReceived":
    selected_network(resp.get("params",{}).get("response",{}),
        resp.get("params",{}).get("requestId"))
   if resp.get("id")==ident:
    if "error" in resp:raise RuntimeError("Selected browser input refused")
    break
  else:raise RuntimeError("Selected browser input timed out")
 return True


def trusted_selected_first_party_player(ws):
 """Perform actual Chromium mouse input on the exact selected first-party embed.

 Outer article Play may create play.javideo.net/embed/<id> but the video
 element can remain readyState=0 until a separate inner user gesture.
 No advertisements, external websites, opaque IDs or source URLs are touched.
 """
 target=None
 for retry in range(26):
  target=evaluate(ws,1100+retry,r"""(() => {
    if(location.hostname!=='play.javideo.net' ||
       !/^\\/(?:embed|player)\\/[A-Za-z0-9_-]{5,100}\\/?$/.test(location.pathname))
       return null;
    const root=document.querySelector(
      '#player,.video-player,.vjs-player,.player-container,video.video-js');
    const button=root?.querySelector(
      '.vjs-big-play-button,button[aria-label="Play"],button[title="Play"],'+
      '.plyr__control[data-plyr="play"],button.play-button') ||
      document.querySelector('.video-js .vjs-big-play-button');
    const control=button || root?.querySelector('video') ||
      (root?.tagName?.toLowerCase()==='video'?root:null) ||
      document.querySelector('video');
    if(!control || control.closest('aside,.ads,.advertisement,'+
       '.related,.recommendations,.sidebar,[data-ad]'))return null;
    control.scrollIntoView({block:'center',inline:'center'});
    const r=control.getBoundingClientRect(),x=Math.round(r.left+r.width/2),
      y=Math.round(r.top+r.height/2);
    if(r.width<5 || r.height<5 || x<0 || y<0 ||
       x>=innerWidth || y>=innerHeight)return null;
    return {x,y,kind:button?'play-control':'selected-video'};
  })()""")
  if isinstance(target,dict) and isinstance(target.get("x"),int):break
  time.sleep(.5)
 if not isinstance(target,dict) or not isinstance(target.get("x"),int):
  return {"activated":False,"target":"not-rendered"}
 for index,(typ,extra) in enumerate((
    ("mouseMoved",{}),
    ("mousePressed",{"button":"left","clickCount":1}),
    ("mouseReleased",{"button":"left","clickCount":1}))):
  ident=1500+index
  ws.send(json.dumps({"id":ident,"method":"Input.dispatchMouseEvent",
      "params":{"type":typ,"x":target["x"],"y":target["y"],**extra}}))
  until=time.monotonic()+12
  while time.monotonic()<until:
   reply=json.loads(ws.recv(timeout=min(4,max(.1,until-time.monotonic()))))
   if reply.get("method")=="Network.responseReceived":
    selected_network(reply.get("params",{}).get("response",{}),
                     reply.get("params",{}).get("requestId"))
   if reply.get("id")==ident:
    if "error" in reply:return {"activated":False,"target":target["kind"]}
    break
  else:return {"activated":False,"target":target["kind"]}
 return {"activated":True,"target":target["kind"]}

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
    # Enable first-party network observation BEFORE the real selected Play
    # click loads its publisher iframe. Previously enabled too late and
    # reported zero response statuses even when a selected frame existed.
    ws.send(json.dumps({"id":9,"method":"Network.enable","params":{}}))
    for _ in range(25):
     ready=json.loads(ws.recv(timeout=7))
     if ready.get("method")=="Network.responseReceived":
      selected_network(ready.get("params",{}).get("response",{}),ready.get("params",{}).get("requestId"))
     if ready.get("id")==9:break
    stats=None
    for step in range(40):
     stats=evaluate(ws,10+step,r"""(() => ({
       ready:document.readyState,page:location.href,
       play:!!document.querySelector('#clickfakeplayer'),
       activePlayer:document.querySelectorAll('.embed-responsive.player:not(.d-none)').length,
       visibleGate:[...document.querySelectorAll('.cf-turnstile,.g-recaptcha,.h-captcha')]
        .some(e=>e.getBoundingClientRect().width>0&&e.getBoundingClientRect().height>0)
     }))()""")
     # Edge initially exposes a fully loaded about:blank document before
     # the publisher navigation commits. That is NOT a site redirect.
     if isinstance(stats,dict) and stats.get("page","").startswith(SELECTED) and (
             stats.get("play") or stats.get("visibleGate")):
      break
     time.sleep(.5)
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
    clicked=trusted_selected_play(ws,930)
    print(json.dumps({"stage":"EXACT_SELECTED_TRUSTED_PLAY",
      "browser_input_accepted":clicked,
      "synthetic_click_used":False}),flush=True)
    assert clicked is True,"Selected player Play control could not be activated"
    found={}
    for step in range(30):
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
     # Repeat the same selected publisher Play button only twice if its
     # JavaScript handler wasn't installed at initial load. Never press
     # adverts, recommendations or extra provider navigation.
     if step in (5,13):
      trusted_selected_play(ws,1000+step*6)
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
      "frame_route_shapes":[{
        "host":urllib.parse.urlsplit(x["uri"]).hostname,
        "path_segments":[segment if segment in {"e","v","embed","player","video","play","watch"}
            else "{opaque}" for segment in urllib.parse.urlsplit(x["uri"]).path.split("/") if segment],
        "has_query":bool(urllib.parse.urlsplit(x["uri"]).query)}
        for x in frames[:4]],
      "exact_selected_streamtape_count":len(set(exact))}),flush=True)
    if found.get("visibleGate"):return 2
    first_party=list(dict.fromkeys(
      x["uri"] for x in frames if
      urllib.parse.urlsplit(x["uri"]).hostname=="play.javideo.net" and
      not x.get("ad")))
    if len(first_party)==1:
     try:
      req=urllib.request.Request(first_party[0],headers={
       "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36",
       "Referer":SELECTED,"Accept-Encoding":"identity"})
      # An OS proxy/TLS failure in urllib is NOT evidence that the
      # publisher-selected iframe did not load inside Chromium.
      # Preserve strict selection, but continue the real in-place frame
      # observation rather than aborting all CDP work.
      try:
       page_response=urllib.request.urlopen(req,timeout=12)
      except Exception as exc:
       cause=getattr(exc,"reason",None)
       print(json.dumps({"stage":"FIRST_PARTY_JAVIDEO_DIRECT_HTTP",
         "http":0,"error_class":type(exc).__name__,
         "network_cause":type(cause).__name__ if cause is not None else None,
         "winerror":getattr(cause,"winerror",None)}),flush=True)
       class _NotFetched:
        status=0
        def read(self,size):return b""
       page_response=contextlib.nullcontext(_NotFetched())
      with page_response as response:
       html=response.read(850000).decode("utf8","replace")
       nested=re.findall(r"""<iframe[^>]+src=["']([^"'<>]{5,2000})""",html,re.I)
       nested_hosts=sorted(set(urllib.parse.urlsplit(
        urllib.parse.urljoin(first_party[0],x)).hostname for x in nested))
       print(json.dumps({"stage":"FIRST_PARTY_JAVIDEO_PLAYER",
         "http":response.status,"bytes":len(html),"iframe_hosts":nested_hosts[:8],
         "video_tags":len(re.findall(r"<video\\b",html,re.I)),
         "hls_literals":len(re.findall(r"\\.m3u8",html,re.I)),
         "mp4_literals":len(re.findall(r"\\.mp4",html,re.I)),
         "script_hosts":sorted(set(urllib.parse.urlsplit(
            urllib.parse.urljoin(first_party[0],x)).hostname
            for x in re.findall(r"""<script[^>]+src=["']([^"'<>]+)""",html,re.I)
            ))[:18],
         "player_signals":{key:len(re.findall(pattern,html,re.I)) for key,pattern in {
            "jwplayer":r"jwplayer","videojs":r"videojs|video-js",
            "dplayer":r"dplayer","plyr":r"plyr","video":r"video",
            "fetch":r"fetch\s*\(","xhr":r"XMLHttpRequest|axios",
            "atob":r"atob\s*\(","base64":r"base64",
            "eval":r"eval\s*\(","packed":r"eval\(function\(p,a,c,k,e,d",
            "playerSetup":r"player\s*\.\s*setup","source":r"source",
            "csrf":r"csrf|nonce","encryption":r"encrypt|decrypt|CryptoJS"
         }.items()}}),flush=True)
       # Structural diagnostics only: this is the SAME first-party embed
       # obtained from the actual Play click, never any unrelated ad player.
       # Do not print opaque media URLs, session tokens, or JS source text.
       inline=re.findall(r"""(?is)<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>""",html)
       active=[script for script in inline if re.search(
         r"fetch\s*\(|videojs\s*\(|\.src\s*\(|source\s*:",script,re.I)]
       print(json.dumps({"stage":"SELECTED_PLAYER_SCRIPT_STRUCTURE",
         "inline_script_count":len(inline),
         "relevant_script_count":len(active),
         "patterns":{needle:sum(len(re.findall(pattern,js,re.I)) for js in active)
            for needle,pattern in {
                "fetch_calls":r"\bfetch\s*\(",
                "videojs_initializers":r"\bvideojs\s*\(",
                "source_assignments":r"\.src\s*\(|\bsources\s*:",
                "media_html":r"\.m3u8|\.mp4",
                "network_errors":r"\b(?:not found|failed to fetch|access denied|forbidden)\b",
                "csrf_or_challenge":r"csrf|captcha|challenge|turnstile",
                "user_action_handlers":r"addEventListener\s*\(|\.on\s*\(",
            }.items()},
         "all_values_redacted":True}),flush=True)
       # Chrome's cross-origin selected iframe may be an independent
       # out-of-process renderer. Observe that ACTUAL frame in place,
       # retaining its original publisher referrer/session, instead of
       # relying only on a new top-level navigation to the embed.
       targets=request(port)
       frame_targets=[x for x in targets if x.get("type")=="iframe" and
           x.get("url","").split("?")[0].rstrip("/")==
               first_party[0].split("?")[0].rstrip("/")]
       active_iframes=[x for x in targets if x.get("type")=="iframe"]
       print(json.dumps({"stage":"ACTUAL_SELECTED_IFRAME_TARGETS",
           "frame_target_count":len(frame_targets),
           "target_types":sorted(set(x.get("type","") for x in targets)),
           "first_party_target":len(frame_targets)==1,
           "iframe_target_shapes":[{
               "host":urllib.parse.urlsplit(x.get("url","")).hostname,
               "scheme":urllib.parse.urlsplit(x.get("url","")).scheme,
               "first_path_segment":(
                  urllib.parse.urlsplit(x.get("url","")).path.split("/")[1]
                  if "/" in urllib.parse.urlsplit(x.get("url","")).path else ""),
               "same_publisher_embed_path":(
                   urllib.parse.urlsplit(x.get("url","")).path.rstrip("/")==
                   urllib.parse.urlsplit(first_party[0]).path.rstrip("/")),
               "query_keys":sorted(urllib.parse.parse_qs(
                   urllib.parse.urlsplit(x.get("url","")).query))[:8]}
               for x in active_iframes[:8]]}),flush=True)
       if len(frame_targets)==1 and frame_targets[0].get("webSocketDebuggerUrl"):
        try:
         with connect(frame_targets[0]["webSocketDebuggerUrl"],
             open_timeout=7,close_timeout=2,max_size=1_000_000) as frame_ws:
          frame_state={}
          for iteration in range(26):
           frame_state=evaluate(frame_ws,500+iteration,r"""(() => ({
             ready:document.readyState,host:location.hostname,
             videoCount:document.querySelectorAll('video').length,
             sources:[...document.querySelectorAll('video')].map(v=>({
               src:v.currentSrc||v.src||
                 v.querySelector('source[src]')?.src||'',
               ready:v.readyState,
               duration:Number.isFinite(v.duration)?Math.round(v.duration):null
             })).slice(0,4)
           }))()""") or {}
           if any(v.get("src") for v in frame_state.get("sources",[])):
            break
           time.sleep(.7)
          print(json.dumps({"stage":"ACTUAL_SELECTED_EMBEDDED_FRAME_RUNTIME",
            "frame_host":frame_state.get("host"),
            "ready":frame_state.get("ready"),
            "video_count":frame_state.get("videoCount"),
            "source_shapes":[{
              "host":urllib.parse.urlsplit(v.get("src","")).hostname,
              "extension":urllib.parse.urlsplit(v.get("src","")).path.rsplit(".",1)[-1][-10:],
              "scheme":urllib.parse.urlsplit(v.get("src","")).scheme,
              "ready":v.get("ready"),"duration_s":v.get("duration")}
              for v in frame_state.get("sources",[])],
            "media_file_verified":False}),flush=True)
        except Exception as exc:
         print(json.dumps({"stage":"ACTUAL_SELECTED_EMBEDDED_FRAME_RUNTIME",
           "error_class":type(exc).__name__}),flush=True)
       # Observe HTTP statuses for ONLY the original first-party
       # player and its already-observed video delivery host. No cookies,
       # media identifiers or signed URL values are logged.
       ws.send(json.dumps({"id":169,"method":"Network.enable","params":{}}))
       for _ in range(16):
        reply=json.loads(ws.recv(timeout=6))
        if reply.get("id")==169:break
       # Inspect ONLY the exact first-party player selected by the
       # publisher's actual Play control, after its JS initializes.
       ws.send(json.dumps({"id":170,"method":"Page.navigate",
          "params":{"url":first_party[0]}}))
       for _ in range(12):
        msg=json.loads(ws.recv(timeout=6))
        if msg.get("id")==170:break
       inner=trusted_selected_first_party_player(ws)
       print(json.dumps({"stage":"TRUSTED_SELECTED_INNER_PLAYER_INPUT",
          "real_chromium_mouse":True,**inner}),flush=True)
       rendered={}
       for iteration in range(24):
        rendered=evaluate(ws,220+iteration,r"""(() => {
          const root=document.querySelector(
            '#player,.video-player,.vjs-player,.player-container,video.video-js');
          const control=root?.querySelector('.vjs-big-play-button,'+
            'button[aria-label="Play"],button[title="Play"]') ||
            (root?.tagName?.toLowerCase()==='video'?
              root.parentElement?.querySelector('.vjs-big-play-button'):null);
          if(control && !control.dataset.asterionProbeActivated) {
            control.dataset.asterionProbeActivated='true';control.click();
          }
          const players=[...document.querySelectorAll('video')];
          const loaded=players.map(v=>{
            if(v.paused){v.muted=true;
              try{const p=v.play();if(p&&p.catch)p.catch(()=>{});}catch(e){}
            }
            return {src:v.currentSrc||v.src||
                v.querySelector('source[src]')?.src||'',
              ready:v.readyState,
              duration:Number.isFinite(v.duration)?Math.round(v.duration):null};
          });
          return {host:location.hostname,pathname:location.pathname,
            ready:document.readyState,videoCount:players.length,
            sources:loaded,frameCount:document.querySelectorAll('iframe').length,
            playerErrors:[...document.querySelectorAll(
              '.vjs-error-display,.vjs-modal-dialog,.video-js .error')]
              .map(e=>(e.textContent||'').trim().slice(0,180))
              .filter(Boolean).slice(0,3),
            resourceHosts:[...new Set(performance.getEntriesByType('resource')
              .map(e=>{try{return new URL(e.name).hostname;}catch(_){return '';}})
              .filter(Boolean))].slice(0,16),
            challenge:/verify you are human|captcha|access denied/i
              .test(document.body?.innerText?.slice(0,2000)||'')};
        })()""") or {}
        if rendered.get("challenge") or any(
           x.get("src") for x in rendered.get("sources",[])):break
        time.sleep(.8)
       sources=rendered.get("sources",[])
       print(json.dumps({"stage":"RENDERED_FIRST_PARTY_SELECTED_PLAYER",
        "host":rendered.get("host"),"page_kind":rendered.get("pathname","").split("/")[:2],
        "ready":rendered.get("ready"),"video_count":rendered.get("videoCount"),
        "iframe_count":rendered.get("frameCount"),
        "challenge":rendered.get("challenge"),
        "player_errors":rendered.get("playerErrors",[]),
        "network_hosts":rendered.get("resourceHosts",[]),
        "sources":[{"host":urllib.parse.urlsplit(x.get("src","")).hostname,
            "scheme":urllib.parse.urlsplit(x.get("src","")).scheme,
            "path_ext":urllib.parse.urlsplit(x.get("src","")).path.rsplit(".",1)[-1][-10:],
            "ready":x.get("ready"),"duration_s":x.get("duration")}
            for x in sources[:4]]}),flush=True)
     except Exception as exc:
      reason=getattr(exc,"reason",None)
      print(json.dumps({"stage":"FIRST_PARTY_JAVIDEO_PLAYER",
       "error_class":type(exc).__name__,
       "network_cause":type(reason).__name__ if reason is not None else None,
       "connection_error_code":getattr(reason,"winerror",None)}),flush=True)
    # Only inspect HTTP 200 application/json responses from the selected
    # publisher's own player /api requests captured in this exact browser
    # session. Output field names and media-host shapes, never signed values.
    api_receipts=[]
    for index,request_id in enumerate(list(dict.fromkeys(
            SELECTED_API_REQUESTS))[:5]):
     try:
      ident=801+index
      ws.send(json.dumps({"id":ident,"method":"Network.getResponseBody",
        "params":{"requestId":request_id}}))
      found_body=None
      limit=time.monotonic()+12
      while time.monotonic()<limit:
       reply=json.loads(ws.recv(timeout=min(4,max(.1,limit-time.monotonic()))))
       if reply.get("method")=="Network.responseReceived":
        selected_network(reply.get("params",{}).get("response",{}),
                         reply.get("params",{}).get("requestId"))
       if reply.get("id")==ident:
        found_body=reply.get("result") or {}
        break
      if not found_body:raise RuntimeError("CDP JSON response unavailable")
      body=found_body.get("body") or ""
      if found_body.get("base64Encoded"):
       body=base64.b64decode(body).decode("utf8","replace")
      if len(body)>800000:raise ValueError("Selected API response too large")
      obj=json.loads(body)
      fields=[];media=[];status={}
      def inspect(item,depth=0):
       if depth>6:return
       if isinstance(item,dict):
        for name,value in list(item.items())[:80]:
         if(re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,35}",str(name)) and
            not re.search(r"secret|token|cookie|key|password",name,re.I)):
          fields.append(name)
         if name.lower() in ("success","ok","status","code","error") and (
              isinstance(value,(int,bool)) or value is None):
          status[name]=value
         inspect(value,depth+1)
       elif isinstance(item,list):
        for value in item[:40]:inspect(value,depth+1)
       elif isinstance(item,str) and item.startswith("https://"):
        u=urllib.parse.urlsplit(item)
        if u.hostname and u.path.lower().endswith((".mp4",".m3u8",".webm",".mpd")):
         media.append({"host":u.hostname,
           "extension":u.path.rsplit(".",1)[-1][:8],"signed":bool(u.query)})
      inspect(obj)
      observed_t=obj.get("t") if isinstance(obj,dict) else None
      api_receipts.append({"valid_json":True,
        "root_kind":type(obj).__name__,"fields":sorted(set(fields))[:50],
        "status_flags":status,
        "published_t_type":type(observed_t).__name__,
        "published_t_length":len(str(observed_t)) if observed_t is not None else 0,
        "published_t_is_url":isinstance(observed_t,str) and
            observed_t.startswith("https://"),
        "media_shapes":media[:8],
        "complete_original_verified":False})
     except Exception as exc:
      api_receipts.append({"valid_json":False,
         "inspection_error":type(exc).__name__,
         "complete_original_verified":False})
    print(json.dumps({"stage":"EXACT_SELECTED_JAVIDEO_JSON_API",
      "captured_requests":len(SELECTED_API_REQUESTS),
      "responses":api_receipts,
      "complete_original_verified":False}),flush=True)
    grouped={}
    for observed in SELECTED_NETWORK:
     label=(observed.get("host"),observed.get("first_route_segment"),
          observed.get("http_status"),observed.get("mime"))
     grouped[label]=grouped.get(label,0)+1
    counts=[{"host":label[0],"first_route_segment":label[1],
             "http":label[2],"mime":label[3],"count":count}
            for label,count in grouped.items()]
    print(json.dumps({"stage":"SELECTED_FIRST_PARTY_VIDEO_NETWORK_STATUS",
      "observed":len(SELECTED_NETWORK),
      "by_source_and_http":counts[:65],
      "non_success":[x for x in counts if
        not isinstance(x["http"],(int,float)) or
        x["http"]<200 or x["http"]>=300][:30],
      "completed_original_verified":False}),flush=True)
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
