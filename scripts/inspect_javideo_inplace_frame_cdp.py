#!/usr/bin/env python3
"""Inspect only the JAVIDEO selected first-party iframe IN PLACE.

This corrects a diagnostic blind spot: no OOPIF CDP target does not mean no
frame. Page.getFrameTree + Page.createIsolatedWorld can inspect same-process
frames without navigating the browser away from the selected publisher article,
preserving its actual Referer/gesture/session. No unrelated ads or source URLs
are exported. Bounded normal user gesture only on the selected embedded video.
"""
import json,re,socket,subprocess,tempfile,time,urllib.parse,urllib.request,shutil,sys
from pathlib import Path
from cdp_stdlib_socket import connect
from verify_javideo_selected_play_cdp import EDGE,SELECTED,request,freeport,trusted_selected_play

def cdp(ws,id,method,params=None,deadline=13):
    ws.send(json.dumps({"id":id,"method":method,"params":params or {}}))
    stop=time.monotonic()+deadline
    while time.monotonic()<stop:
        try:msg=json.loads(ws.recv(timeout=min(4,max(.1,stop-time.monotonic()))))
        except TimeoutError:continue
        if msg.get("id")!=id:continue
        if "error" in msg:return {"error_code":msg["error"].get("code")}
        return msg.get("result") or {}
    return {"error_code":"timeout"}

def js(ws,id,expr,ctx=None):
    params={"expression":expr,"returnByValue":True,"awaitPromise":True}
    if ctx is not None:params["contextId"]=ctx
    result=cdp(ws,id,"Runtime.evaluate",params,deadline=18)
    if result.get("exceptionDetails"):return None
    return result.get("result",{}).get("value")

def matching_frame(tree,exact):
    out=[]
    def walk(node):
        frame=node.get("frame") or {}
        u=urllib.parse.urlsplit(frame.get("url",""))
        if(u.scheme=="https" and u.hostname=="play.javideo.net"
           and re.fullmatch(r"/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?",u.path)
           and not u.query and u.geturl()==exact):
            out.append(frame.get("id"))
        for child in node.get("childFrames") or []:walk(child)
    walk(tree)
    return [x for x in out if x]

def main():
    if not EDGE.is_file():raise RuntimeError("Owner Edge missing")
    folder=tempfile.mkdtemp(prefix="asterion_javideo_inframe_")
    port=freeport()
    browser=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
       "--no-first-run","--no-default-browser-check","--disable-extensions",
       "--disable-background-networking","--mute-audio",
       "--remote-debugging-port="+str(port),"--remote-allow-origins=*",
       "--user-data-dir="+folder,SELECTED],
       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        tab=None
        for _ in range(80):
            try:
                tab=next((x for x in request(port) if x.get("type")=="page"
                    and x.get("url","").startswith(SELECTED)),None)
                if tab:break
            except Exception:pass
            time.sleep(.25)
        if not tab:raise RuntimeError("Selected JAVIDEO tab not loaded")
        with connect(tab["webSocketDebuggerUrl"],open_timeout=8,close_timeout=2,
                     max_size=2_000_000) as ws:
            for i,method in ((10,"Page.enable"),(11,"Runtime.enable"),(12,"Network.enable")):
                r=cdp(ws,i,method,deadline=12)
                if "error_code" in r:raise RuntimeError("Selected browser protocol not enabled")
            loaded=False
            for i in range(40):
                s=js(ws,40+i,r"""(() => ({
                    host:location.hostname, path:location.pathname,
                    play:!!document.querySelector('#clickfakeplayer')
                }))()""")
                if s and s.get("host")=="javideo.net" and s.get("path")=="/en/fc2ppv-4994603" and s.get("play"):
                    loaded=True;break
                time.sleep(.4)
            if not loaded:raise RuntimeError("Selected page Play unavailable")
            clicked=trusted_selected_play(ws,500)
            if not clicked:raise RuntimeError("Selected publisher Play not activated")
            exact=None
            for i in range(36):
                state=js(ws,100+i,r"""(() => {
                    const frames=[...document.querySelectorAll(
                       '.embed-responsive.player:not(.d-none) iframe[src]')]
                       .filter(x=>!x.closest('aside,.ads,.advertisement,.related,.sidebar'));
                    return {frames:frames.map(x=>new URL(x.getAttribute('src'),location.href).href)};
                })()""")
                if state and len(state.get("frames",[]))==1:
                    test=urllib.parse.urlsplit(state["frames"][0])
                    if test.scheme=="https" and test.hostname=="play.javideo.net" and re.fullmatch(
                        r"/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?",test.path) and not test.query:
                        exact=state["frames"][0];break
                time.sleep(.6)
            if not exact:
                print(json.dumps({"stage":"SELECTED_JAVIDEO_SAME_PROCESS_FRAME",
                    "publisher_play":"clicked","selected_iframe":False,
                    "full_original":False}),flush=True)
                return 4
            matches=[]
            for i in range(12):
                tree=cdp(ws,200+i,"Page.getFrameTree")
                matches=matching_frame(tree.get("frameTree") or {},exact)
                if len(matches)==1:break
                time.sleep(.5)
            print(json.dumps({"stage":"SELECTED_JAVIDEO_FRAME_TREE",
                "exact_selected_frame_count":len(matches),"no_OOPIF_target_required":True}),flush=True)
            if len(matches)!=1:return 4
            context=cdp(ws,300,"Page.createIsolatedWorld",{
                "frameId":matches[0],"worldName":"asterion_selected_provenance_probe"})
            ctx=context.get("executionContextId")
            if not isinstance(ctx,int):
                print(json.dumps({"stage":"SELECTED_JAVIDEO_ISOLATED_WORLD",
                    "context_created":False,"error_code":context.get("error_code")}),flush=True)
                return 4
            inspect=r"""(() => {
                const v=document.querySelector('video');
                const click=document.querySelector(
                  '#player .vjs-big-play-button,.video-js .vjs-big-play-button,'+
                  '.player-container button[aria-label="Play"],'+
                  '#player button[aria-label="Play"]')||v;
                const b=click?.getBoundingClientRect();
                const src=v?.currentSrc||v?.getAttribute('src')||
                    v?.querySelector('source[src]')?.src||'';
                const u=(()=>{try{return new URL(src,location.href);}catch(_){return null;}})();
                return {host:location.hostname,refHost:(()=>{try{return new URL(document.referrer).hostname}catch(_){return ''}})(),
                  ready:document.readyState,videoCount:document.querySelectorAll('video').length,
                  videoReady:v?.readyState??null,videoNetwork:v?.networkState??null,
                  videoError:v?.error?.code??null,duration:Number.isFinite(v?.duration)?Math.round(v.duration):null,
                  srcHost:u?.hostname||null,srcExt:u?.pathname.split('.').pop().slice(0,8)||null,
                  srcPresent:!!src,playerTarget:click===v?'video':'button',
                  rect:b?{x:b.left,y:b.top,w:b.width,h:b.height}:null};
            })()"""
            state=js(ws,400,inspect,ctx)
            if not state:raise RuntimeError("Selected isolated frame did not evaluate")
            print(json.dumps({"stage":"SELECTED_JAVIDEO_INPLACE_INITIAL",
                **{k:v for k,v in state.items() if k!="rect"},
                "media_verified":False}),flush=True)
            if not state.get("srcPresent") and state.get("rect"):
                rect=state["rect"]
                parent=js(ws,401,r"""(() => {
                    const f=document.querySelector(
                      '.embed-responsive.player:not(.d-none) iframe[src]');
                    if(!f)return null;
                    f.scrollIntoView({block:'center',inline:'center'});
                    const r=f.getBoundingClientRect();
                    return {x:r.left,y:r.top,w:r.width,h:r.height,
                      viewportW:innerWidth,viewportH:innerHeight};
                })()""")
                if parent and rect["w"]>=8 and rect["h"]>=8 and parent["w"]>=8 and parent["h"]>=8:
                    x=round(parent["x"]+rect["x"]+rect["w"]/2)
                    y=round(parent["y"]+rect["y"]+rect["h"]/2)
                    if 0<x<parent["viewportW"] and 0<y<parent["viewportH"]:
                        ok=True
                        for i,(kind,extra) in enumerate((
                             ("mouseMoved",{}),
                             ("mousePressed",{"button":"left","clickCount":1}),
                             ("mouseReleased",{"button":"left","clickCount":1}))):
                            answer=cdp(ws,440+i,"Input.dispatchMouseEvent",
                                {"type":kind,"x":x,"y":y,**extra})
                            if "error_code" in answer:ok=False;break
                        print(json.dumps({"stage":"SELECTED_JAVIDEO_INPLACE_GESTURE",
                            "same_selected_frame":True,"real_mouse":ok}),flush=True)
                        for i in range(25):
                            state=js(ws,480+i,inspect,ctx) or {}
                            if state.get("srcPresent") and (state.get("videoReady") or 0)>=1:break
                            time.sleep(.55)
                        print(json.dumps({"stage":"SELECTED_JAVIDEO_INPLACE_AFTER_GESTURE",
                            **{k:v for k,v in state.items() if k!="rect"},
                            "media_verified":False}),flush=True)
            return 0 if state.get("srcPresent") and state.get("duration") and state["duration"]>90 else 4
    finally:
        subprocess.run(["taskkill","/PID",str(browser.pid),"/T","/F"],
                       capture_output=True,timeout=14)
        try:browser.wait(timeout=4)
        except Exception:pass
        shutil.rmtree(folder,ignore_errors=True)

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as exc:
        print(json.dumps({"stage":"SELECTED_JAVIDEO_SAME_PROCESS_FRAME",
            "status":"NOT_VERIFIED","error_type":type(exc).__name__}),flush=True)
        sys.exit(6)
