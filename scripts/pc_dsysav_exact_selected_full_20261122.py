#!/usr/bin/env python3
"""Complete-file PC certification on the actual owner-supplied dsysav.com/en site.
The selected article is taken from the real publisher catalog; no guessed ID,
other DSAV domains, previews, download shortcuts or ad players qualify.
"""
import hashlib,json,re,subprocess,sys,tempfile,time,urllib.parse
from pathlib import Path
from pc_full_selected_provider_acceptance_20261122 import safe_url,host
from selenium import webdriver
from selenium.webdriver.chrome.options import Options

BASE="https://dsysav.com/en"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
SITE={"dsysav.com","www.dsysav.com"}
result={"provider":"dsysav.com","catalog":BASE,"complete_selected_original":False}
options=Options()
for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage",
             "--disable-gpu","--mute-audio","--window-size=1280,850"):
    options.add_argument(flag)
options.set_capability("goog:loggingPrefs",{"performance":"ALL"})
browser=webdriver.Chrome(options=options)

def approved_article(raw):
    try:
        url=urllib.parse.urljoin(BASE,raw)
        u=urllib.parse.urlsplit(url)
        return url if (u.scheme=="https" and u.hostname in SITE and
          not u.username and not u.password and u.port in (None,443) and
          re.fullmatch(r"/en/watch/?",u.path) and
          bool(u.query) and not u.fragment) else None
    except Exception:
        return None

def complete_file(url,referer,expected_runtime):
    with tempfile.TemporaryDirectory(prefix="asterion-dsysav-pc-") as td:
        out=Path(td)
        args=[sys.executable,"-m","yt_dlp","--no-playlist","--no-part",
            "--no-mtime","--no-warnings","--retries","2",
            "--fragment-retries","2","--abort-on-unavailable-fragments",
            "--socket-timeout","20","--concurrent-fragments","4",
            "--max-filesize","1200M","--format","best",
            "--user-agent",UA,"--add-header","Referer:"+referer,
            "--add-header","Origin:https://dsysav.com",
            "-o",str(out/"selected-original.%(ext)s"),url]
        try:
            res=subprocess.run(args,capture_output=True,text=True,timeout=540)
        except subprocess.TimeoutExpired:
            raise RuntimeError("FULL_SELECTED_ORIGINAL_TIMED_OUT")
        if res.returncode:
            tail=re.sub(r"https?://\S+","[redacted]",(res.stdout+"\n"+res.stderr)[-650:])
            raise RuntimeError("SELECTED_ORIGINAL_TRANSFER_FAILED:"+tail)
        files=[p for p in out.iterdir() if p.is_file() and p.stat().st_size>30_000_000
            and not p.name.endswith((".part",".ytdl"))]
        if len(files)!=1:raise RuntimeError("NO_SINGLE_COMPLETED_FULL_FILE")
        f=files[0]
        cmd=["ffprobe","-v","error","-show_entries",
            "format=duration,size:stream=codec_type","-of","json",str(f)]
        meta=json.loads(subprocess.check_output(cmd,text=True,timeout=30))
        seconds=float(meta.get("format",{}).get("duration") or 0)
        streams=[x.get("codec_type") for x in meta.get("streams",[])]
        if "audio" not in streams or "video" not in streams:
            raise RuntimeError("FILE_MISSING_VIDEO_OR_AUDIO")
        if seconds<600 or abs(seconds-expected_runtime)>max(15,expected_runtime*.10):
            raise RuntimeError("FULL_DURATION_MISMATCH_OR_PREVIEW")
        sha=hashlib.sha256()
        with f.open("rb") as inp:
            for block in iter(lambda:inp.read(1048576),b""):sha.update(block)
        return {"complete_selected_original":True,"bytes":f.stat().st_size,
            "duration_seconds":round(seconds,3),"tracks":streams,
            "sha256":sha.hexdigest()}

try:
    browser.set_page_load_timeout(26)
    try:browser.get(BASE)
    except Exception:pass
    selected=None
    for attempt in range(34):
        all_urls=browser.execute_script("""return [...document.querySelectorAll('a[href]')]
          .filter(a=>!a.closest('aside,.related,.recommendations,.ads,.advertisement,[data-ad]'))
          .map(a=>a.href);""") or []
        candidates=list(dict.fromkeys(x for x in (approved_article(u) for u in all_urls) if x))
        if candidates:
            selected=candidates[0]
            result["public_watch_count"]=len(candidates)
            break
        time.sleep(.75)
    if not selected:raise RuntimeError("NO_PUBLISHER_SELECTED_WATCH_LINK_ON_DSYSAV_EN")
    result["selected_article"]=selected
    result["selected_host"]=host(selected)
    # This is the actual first watch anchor in the owner-supplied English
    # catalog, not a manufactured ID or a separate DSAV brand domain.
    try:browser.get_log("performance")
    except Exception:pass
    browser.execute_cdp_cmd("Network.enable",{})
    # Follow the site's actual selected watch control in the already
    # initialized browser; a cold URL navigation can leave SPA video empty.
    from selenium.webdriver.common.by import By
    controls=[x for x in browser.find_elements(By.CSS_SELECTOR,"a[href]")
      if approved_article(x.get_attribute("href") or "")==selected]
    try:
        if controls:
            browser.execute_script("arguments[0].scrollIntoView({block:'center'});",controls[0])
            controls[0].click()
        else:browser.get(selected)
    except Exception:
        try:browser.get(selected)
        except Exception:pass
    if host(browser.current_url) not in SITE:
        raise RuntimeError("SELECTED_WATCH_REDIRECTED_TO_OTHER_BRAND")
    result["browser_selected_route"]=urllib.parse.urlsplit(browser.current_url).path
    result["browser_selected_query_keys"]=sorted(urllib.parse.parse_qs(urllib.parse.urlsplit(browser.current_url).query))
    result["browser_selected_matches"]=browser.current_url==selected
    durations=[]
    manifests=set()
    direct=set()
    player_clicked=False
    response_shapes={}
    selected_api_requests=[]
    for tick in range(50):
        if not player_clicked:
            # The real LOAD text is a catalog pagination button, NOT media.
            # Click only the one actual selected watch video's visible poster.
            try:
                videos=[x for x in browser.find_elements(By.CSS_SELECTOR,"main video,#player video,video")
                  if x.is_displayed() and x.is_enabled()]
                if len(videos)==1:
                    browser.execute_script("arguments[0].scrollIntoView({block:'center'});",videos[0])
                    videos[0].click()
                    player_clicked=True
                    result["selected_video_click"]=True
            except Exception as error:
                result["selected_video_click_error"]=type(error).__name__
        if not player_clicked:
            try:
                control=browser.find_elements(By.CSS_SELECTOR,
                  "main .vjs-big-play-button,main .jw-icon-display,"
                  "main button[aria-label='Play'],main button[title='Play'],"
                  "#player .vjs-big-play-button,.video-player .vjs-big-play-button")
                visible=[x for x in control if x.is_displayed() and x.is_enabled()]
                if len(visible)==1:
                    visible[0].click()
                    player_clicked=True
            except Exception:pass
        state=browser.execute_script("""return (()=>{
          const root=document.querySelector('main')||document.querySelector('#app')||document.body;
          const v=[...root.querySelectorAll('video')].filter(v=>
            !v.closest('aside,.related,.recommendations,.ads,.advertisement,[data-ad]'));
          if(v.length===1&&v[0].paused)try{const p=v[0].play();p?.catch?.(()=>{});}catch(_){}
          return v.map(el=>({source:el.currentSrc||el.src||'',
            duration:Number.isFinite(el.duration)?el.duration:0})).slice(0,7);
        })()""") or []
        long=[x for x in state if float(x.get("duration") or 0)>=600]
        if len(long)==1:
            duration=float(long[0]["duration"])
            durations.append(duration)
            media=long[0].get("source") or ""
            if safe_url(media) and re.search(r"(?i)\.(?:mp4|m3u8)$",urllib.parse.urlsplit(media).path):
                direct.add(media)
        try:
            for evt in browser.get_log("performance"):
                item=json.loads(evt["message"])["message"]
                if item.get("method")!="Network.responseReceived":continue
                response=item.get("params",{}).get("response",{})
                url=response.get("url") or ""
                if not safe_url(url) or int(response.get("status") or 0)!=200:continue
                u=urllib.parse.urlsplit(url)
                mime=(response.get("mimeType") or "").lower()
                parsed_url=urllib.parse.urlsplit(url)
                if parsed_url.hostname=="api.dsav.app" and mime.startswith("application/json"):
                    selected_api_requests.append(item.get("params",{}).get("requestId"))
                if len(response_shapes)<45 and parsed_url.hostname:
                    identifier=(parsed_url.hostname,parsed_url.path[:80],int(response.get("status") or 0),mime[:48])
                    response_shapes[str(identifier)]=response_shapes.get(str(identifier),0)+1
                if (u.path.lower().endswith(".m3u8") or
                    "application/vnd.apple.mpegurl" in mime or "application/x-mpegurl" in mime):
                    if not re.search("preview|trailer|teaser|sample",u.path,re.I):
                        manifests.add(url)
        except Exception:pass
        if durations and (len(manifests)==1 or len(direct)==1):break
        time.sleep(.75)
    result["selected_player_duration_seconds"]=round(max(durations),3) if durations else None
    result["selected_player_play_clicked"]=player_clicked
    # Inspect the first-party JSON returned during the exact selected watch
    # session, without logging URLs/tokens or treating recommendation data as
    # selected media. This distinguishes a failed player API from CSS issues.
    api_signals=[]
    for request_id in list(dict.fromkeys(selected_api_requests))[:4]:
        if not request_id:continue
        try:
            payload=browser.execute_cdp_cmd("Network.getResponseBody",{"requestId":request_id})
            raw=payload.get("body","")
            if payload.get("base64Encoded"):
                import base64
                raw=base64.b64decode(raw).decode("utf-8","replace")
            parsed=json.loads(raw)
            paths=[]
            def describe(value,path="",depth=0):
                if depth>6 or len(paths)>=45:return
                if isinstance(value,dict):
                    for key,sub in list(value.items())[:45]:describe(sub,path+"."+str(key),depth+1)
                elif isinstance(value,list):
                    for sub in value[:2]:describe(sub,path+"[]",depth+1)
                elif isinstance(value,str):
                    if re.search(r"(?i)(?:src|media|stream|play|video|url|error|message|status|duration)",path):
                        host_name=urllib.parse.urlsplit(value).hostname if value.startswith(("https://","http://")) else None
                        paths.append({"field":path[:90],"type":"url" if host_name else "string",
                                      "host":host_name,"length":len(value)})
                elif isinstance(value,(int,float)):
                    if re.search(r"(?i)(?:duration|seconds|status|code|error)",path):
                        paths.append({"field":path[:90],"value":value})
            describe(parsed)
            api_signals.append({"top_level_keys":list(parsed)[:35] if isinstance(parsed,dict) else [],
                                "response_bytes":len(raw),"signals":paths[:45]})
        except Exception as error:
            api_signals.append({"reader_error":type(error).__name__})
    result["selected_watch_api_shapes"]=api_signals
    result["media_network_shapes"]={k:v for k,v in response_shapes.items() if
      "m3u8" in k or "mp4" in k or "json" in k or "video" in k or "api" in k or "stream" in k}
    result["selected_player_structure"]=browser.execute_script("""return {
      videos:[...document.querySelectorAll('video')].slice(0,3).map(v=>({
        attrs:[...v.attributes].map(a=>a.name).slice(0,18),
        parent_class:(v.parentElement?.className||'').toString().slice(0,90),
        grandparent_class:(v.parentElement?.parentElement?.className||'').toString().slice(0,90),
        poster:!!v.poster,source_count:v.querySelectorAll('source').length
      })),
      player_actions:[...document.querySelectorAll('main button,main [role=button],#player button')]
        .filter(x=>!x.closest('.pagination-container,aside,.related,.ads,[data-ad]'))
        .slice(0,18).map(x=>({tag:x.tagName,kind:(x.className||'').toString().slice(0,60),
          label:(x.getAttribute('aria-label')||x.textContent||'').trim().slice(0,45)}))
    };""")
    result["selected_dom"]=browser.execute_script("""return {
      load_controls:[...document.querySelectorAll('button,[role="button"],input[type="button"]')]
        .filter(b=>(b.innerText||b.textContent||b.value||'').trim()==='LOAD')
        .slice(0,5).map(b=>({tag:b.tagName,disabled:!!b.disabled,
          aria:b.getAttribute('aria-disabled'),
          rendered:(r=>r.width>0&&r.height>0)(b.getBoundingClientRect()),
          parent_class:(b.parentElement?.className||'').toString().slice(0,90)})),
      video_state:[...document.querySelectorAll('video')].slice(0,3)
        .map(v=>({ready:v.readyState,network:v.networkState,
          error:v.error?.code||null,paused:v.paused,
          has_src:!!(v.currentSrc||v.src),poster:!!v.poster})),
      video_count:document.querySelectorAll('video').length,
      iframe_hosts:[...document.querySelectorAll('iframe[src]')].slice(0,10)
        .map(x=>{try{return new URL(x.src).hostname}catch(_){return ''}}),
      player_buttons:[...document.querySelectorAll('#player button,main button')]
        .slice(0,16).map(b=>(b.getAttribute('aria-label')||b.title||b.textContent||'').trim().slice(0,30)),
      body_hint:(document.body?.innerText||'').slice(0,150)
    };""")
    result["observed_hls_count"]=len(manifests)
    result["observed_direct_count"]=len(direct)
    if not durations:raise RuntimeError("SELECTED_PLAYER_HAS_NO_VERIFIED_LONG_DURATION")
    source=None
    if len(manifests)==1:source=next(iter(manifests))
    elif not manifests and len(direct)==1:source=next(iter(direct))
    if not source:raise RuntimeError("NO_SINGLE_ATTRIBUTABLE_SELECTED_MEDIA_SOURCE")
    result["selected_source_host"]=host(source)
    result["source_attribution"]="first watch link on dsysav.com/en and same selected long player"
    result.update(complete_file(source,selected,max(durations)))
except Exception as error:
    result["reason"]=re.sub(r"https?://\S+","[redacted]",str(error))[:330]
finally:
    browser.quit()
    Path("pc-dsysav-selected-full-receipt.json").write_text(json.dumps(result,indent=2,sort_keys=True))
    print(json.dumps(result,sort_keys=True),flush=True)
if not result["complete_selected_original"]:sys.exit(2)
