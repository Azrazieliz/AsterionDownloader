#!/usr/bin/env python3
"""Normal first-party click of exact owner-selected MP4Hentai Mirrored.to link.

Uses selected publisher article's official Mirror host/ID and existing Edge.
Clicks only Mirrored.to's own "Click to view download links" control once,
waits for ordinary site JavaScript, then reports non-ad, publisher-selected
actual file-host link shapes. No CAPTCHA solving, token/URL export, media save
or unrelated listing/advertisement extraction.
"""
import json,os,re,socket,subprocess,sys,tempfile,time,urllib.parse,urllib.request,shutil
from pathlib import Path
from cdp_stdlib_socket import connect
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
BASE="https://www.mirrored.to/files/DF7298C6/"
ALLOWED={"www.mirrored.to","mirrored.to"}
def freeport():
    with socket.socket() as x:x.bind(("127.0.0.1",0));return x.getsockname()[1]
def tabs(port):
    with urllib.request.urlopen("http://127.0.0.1:"+str(port)+"/json",timeout=3) as r:
        return json.load(r)
def eval(ws,i,js):
    ws.send(json.dumps({"id":i,"method":"Runtime.evaluate",
            "params":{"expression":js,"returnByValue":True,"awaitPromise":True}}))
    for _ in range(80):
        resp=json.loads(ws.recv(timeout=6))
        if resp.get("id")!=i:continue
        if "exceptionDetails" in resp.get("result",{}):
            return {"eval_error":True}
        return resp.get("result",{}).get("result",{}).get("value")
    raise RuntimeError("ordinary selected mirror browser timed out")
def main():
    if not EDGE.is_file():raise RuntimeError("existing Windows Edge missing")
    folder=tempfile.mkdtemp(prefix="asterion_owner_mp4hentai_mirror_")
    port=freeport()
    proc=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
       "--no-first-run","--no-default-browser-check","--disable-extensions",
       "--disable-background-networking","--mute-audio",
       "--remote-debugging-port="+str(port),"--remote-allow-origins=*",
       "--user-data-dir="+folder,BASE],
       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        target=None
        for _ in range(75):
            try:
                target=next((x for x in tabs(port) if x.get("type")=="page" and
                    "mirrored.to" in x.get("url","")),None)
                if target:break
            except Exception:pass
            if proc.poll() is not None:break
            time.sleep(.25)
        if not target:raise RuntimeError("selected Mirrored.to page did not open")
        with connect(target["webSocketDebuggerUrl"],open_timeout=8,
            close_timeout=2,max_size=2_000_000) as ws:
            state={}
            for i in range(40):
                state=eval(ws,20+i,r"""(() => {
                    const link=document.querySelector('a#download-link');
                    return {ready:document.readyState,host:location.hostname,
                      path:location.pathname,has_control:!!link,
                      link_href:link?.href||null,
                      controls:[...document.querySelectorAll('a[href]')]
                       .filter(x=>/download|hosting links|file|upfiles|mirror/i
                         .test(x.textContent||'')).length};
                })()""") or {}
                if state.get("has_control"):break
                time.sleep(.3)
            print(json.dumps({"provider":"mp4hentai",
                "stage":"EXACT_OWNER_MIRROR_NORMAL_BROWSER",
                "actual_host":state.get("host"),
                "has_download_control":state.get("has_control"),
                "visible_download_controls":state.get("controls",0)}),flush=True)
            href=state.get("link_href")
            if state.get("host") not in ALLOWED or not href:
                return 3
            url=urllib.parse.urlsplit(href)
            if (url.scheme!="https" or url.hostname not in ALLOWED or
                url.path!="/files/DF7298C6/" or
                urllib.parse.parse_qs(url.query).get("dl")!=["0"]):
                raise RuntimeError("selected Mirrored control links outside official file")
            clicked=eval(ws,100,r"""(() => {
                const link=document.querySelector('a#download-link');
                if(!link)return false;
                if(link.dataset.asterionClicked)return false;
                link.dataset.asterionClicked='1';
                link.click();return true;
            })()""")
            if clicked is not True:raise RuntimeError("selected host button not clicked")
            result={}
            for iteration in range(48):
                result=eval(ws,200+iteration,r"""(() => ({
                  host:location.hostname,path:location.pathname,
                  placeholder:/fetching hosting links/i
                    .test(document.body?.innerText||''),
                  gates:!!document.querySelector(
                    '.cf-turnstile,.g-recaptcha,.h-captcha,form#file-captcha'),
                  anchors:[...document.querySelectorAll('a[href]')]
                    .filter(x=>/download|get link|upfiles|mirror|file host|direct/i
                      .test((x.textContent||'')+' '+(x.title||'')))
                    .filter(x=>!x.closest(
                      'aside,.ads,.related,.recommendations,.sidebar,[data-ad]'))
                    .slice(0,38).map(x=>({
                      text:(x.textContent||'').trim().slice(0,70),
                      host:new URL(x.href).hostname,
                      path:new URL(x.href).pathname,
                      query:new URL(x.href).search
                    }))
                }))()""") or {}
                if result.get("host") not in ALLOWED:break
                meaningful=[x for x in result.get("anchors",[]) if
                    x.get("host") not in ALLOWED and x.get("host")]
                if meaningful and not result.get("placeholder"):break
                time.sleep(.5)
            links=result.get("anchors") or []
            shapes=[]
            for x in links:
                host=x.get("host","")
                if not host or re.search("ads|analytics|doubleclick",host,re.I):
                    continue
                raw_path=x.get("path") or ""
                shapes.append({"title":x.get("text","")[:45],
                  "host":host,"first_route":raw_path.strip("/").split("/")[0][:30],
                  "query_keys":sorted(urllib.parse.parse_qs(
                    x.get("query") or "").keys())[:7],
                  "is_selected_mirror":host in ALLOWED})
            print(json.dumps({"provider":"mp4hentai",
                "stage":"EXACT_CLICKED_OWNER_MIRROR_HOSTERS",
                "browser_host":result.get("host"),
                "same_selected_file":result.get("path")=="/files/DF7298C6/",
                "placeholder":result.get("placeholder"),
                "challenge_detected":result.get("gates"),
                "published_link_count":len(shapes),
                "link_shapes":shapes[:25],
                "complete_original_verified":False}),flush=True)
            return 0 if result.get("host") in ALLOWED else 3
    finally:
        subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],
          capture_output=True,timeout=12)
        try:proc.wait(timeout=4)
        except Exception:pass
        shutil.rmtree(folder,ignore_errors=True)
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"mp4hentai",
          "status":"SELECTED_MIRROR_CLICK_UNVERIFIED",
          "error_type":type(e).__name__}),flush=True)
        sys.exit(4)
