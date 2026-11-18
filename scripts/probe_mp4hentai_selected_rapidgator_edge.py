#!/usr/bin/env python3
"""Inspect the owner's exact selected MP4Hentai Rapidgator mirror in Edge.

The public Rapidgator filename can contain brackets and parentheses. This
normalizes only URL encoding of the already-published selected file link.
No account login, ad clicks, CAPTCHA solving, token export, guessed mirrors,
or unrelated media are attempted. HTML alone is never media acceptance.
"""
import json
import re
import sys
import subprocess
import shutil
import tempfile
import time
import urllib.parse
from inspect_mp4hentai_mirror_normal_click import (
    main as selected,EDGE,freeport,tabs,eval as cdp_eval)
from cdp_stdlib_socket import connect

def main():
    links=selected(return_selected_rapidgator=True)
    if not isinstance(links,list) or len(set(links))!=1:
        raise RuntimeError("Selected Rapidgator hoster not unique")
    u=urllib.parse.urlsplit(links[0])
    path=urllib.parse.quote(u.path,safe="/-_.~!$&'()*+,;=:@%")
    target=urllib.parse.urlunsplit((u.scheme,u.netloc,path,u.query,u.fragment))
    p=urllib.parse.urlsplit(target)
    if (p.scheme!="https" or p.hostname not in ("rapidgator.net","www.rapidgator.net")
        or p.username or p.password or p.port or p.fragment or p.query or
        not re.fullmatch(
          r"/file/[A-Za-z0-9_-]{6,100}(?:/[A-Za-z0-9%._~!$&'()*+,;=:@-]{1,210})?/?",
          p.path)):
        raise RuntimeError("Selected Rapidgator file route invalid")
    folder=tempfile.mkdtemp(prefix="asterion_selected_rapidgator_")
    port=freeport()
    proc=subprocess.Popen([str(EDGE),"--headless=new","--disable-gpu",
       "--no-first-run","--no-default-browser-check","--disable-extensions",
       "--disable-background-networking","--remote-debugging-port="+str(port),
       "--remote-allow-origins=*","--user-data-dir="+folder,target],
       stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    try:
        page=None
        for i in range(90):
            try:
                page=next((x for x in tabs(port) if x.get("type")=="page"
                           and x.get("url","").startswith("https://")),None)
                if page:break
            except Exception:pass
            if proc.poll() is not None:break
            time.sleep(.3)
        if not page:raise RuntimeError("Selected file hoster tab not created")
        with connect(page["webSocketDebuggerUrl"],open_timeout=8,
                     close_timeout=2,max_size=1_000_000) as ws:
            state={}
            for i in range(32):
                state=cdp_eval(ws,180+i,r"""(() => {
                  const h=location.hostname.replace(/^www\\./,'');
                  const path=location.pathname;
                  const forms=[...document.querySelectorAll('form')].slice(0,15);
                  const anchors=[...document.querySelectorAll('a[href]')]
                    .filter(x=>/download|get file|free/i.test(
                      (x.innerText||'')+' '+(x.title||'')))
                    .filter(x=>!x.closest(
                      'aside,.ads,.advertisement,.sidebar,.related,[data-ad]'))
                    .slice(0,18);
                  return {host:h,scheme:location.protocol,
                    routeKind:path.split('/').filter(Boolean)[0]||'',
                    routeSegments:path.split('/').filter(Boolean).length,
                    ready:document.readyState,
                    htmlBytes:document.documentElement?.outerHTML?.length||0,
                    visibleChallenge:!!document.querySelector(
                      '.cf-turnstile,.g-recaptcha,.h-captcha,form#file-captcha,'+
                      'input[name*=captcha],iframe[src*=captcha]'),
                    challengeText:/captcha|verify you are human|checking your browser|access denied|forbidden/i.test(
                      (document.title||'')+' '+(document.body?.innerText||'').slice(0,900)),
                    forms:forms.map(f=>({method:(f.method||'').toLowerCase(),
                      host:(()=>{try{return new URL(f.action,location.href).hostname;}catch(_){return '';}})(),
                      fieldTypes:[...f.querySelectorAll('input')].map(e=>e.type).slice(0,20)})),
                    downloadLinks:anchors.map(a=>{
                      const u=new URL(a.href,location.href);
                      return {host:u.hostname,ext:u.pathname.split('.').pop().slice(0,7),
                        sameHost:u.hostname===location.hostname,
                        hasQuery:!!u.search};
                    }),
                    videoTags:document.querySelectorAll('video').length};
                })()""") or {}
                if state.get("ready")=="complete" and state.get("htmlBytes",0)>150:
                    break
                time.sleep(.6)
            print(json.dumps({"stage":"SELECTED_RAPIDGATOR_EDGE_BROWSER",
                "same_selected_mirror":True,"state":state,
                "complete_original_verified":False}),flush=True)
            return 0 if (state.get("host")=="rapidgator.net"
                         and state.get("htmlBytes",0)>150) else 4
    finally:
        subprocess.run(["taskkill","/PID",str(proc.pid),"/T","/F"],
            capture_output=True,timeout=12)
        try:proc.wait(timeout=3)
        except Exception:pass
        shutil.rmtree(folder,ignore_errors=True)

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"stage":"SELECTED_RAPIDGATOR_EDGE_BROWSER",
                          "status":"NOT_VERIFIED","error_type":type(e).__name__}),flush=True)
        sys.exit(6)
