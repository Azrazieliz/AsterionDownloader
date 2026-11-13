#!/usr/bin/env python3
"""Attempt genuine public-site video playback in a real headless Chrome browser.
No automatic content downloads, authentication bypasses, or media artifacts.
Store only HTTP status, MIME, host and first-party player diagnostics.
"""
import json
import re
import time
import urllib.parse

SITES = {
    "Jav Guru": "https://jav.guru/1076811/mukc-135-off-pako-creampie-breeding-photo-session-where-we-defile-a-beautiful-pure-idol-whos-obedient-like-a-doll-however-we-please-shitara-yuuhi/",
    "Archivebate": "https://archivebate.com/",
}
out = {}
try:
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.common.by import By
except Exception as ex:
    raise SystemExit("Selenium unavailable: "+str(ex))

opts=Options()
for arg in ("--headless=new","--no-sandbox","--disable-gpu",
            "--disable-dev-shm-usage","--mute-audio",
            "--autoplay-policy=no-user-gesture-required",
            "--window-size=1280,900"):
    opts.add_argument(arg)
opts.set_capability("goog:loggingPrefs",{"performance":"ALL"})
driver=webdriver.Chrome(options=opts)
driver.set_page_load_timeout(32)
driver.implicitly_wait(0)
def evidence(name,label):
    html=driver.page_source
    values=driver.execute_script("""
      return {
        players: [...document.querySelectorAll('video,source,iframe')]
          .slice(0,50).map(el=>({
            tag:el.tagName,
            src:el.currentSrc||el.getAttribute('src')||'',
            poster:el.poster||el.getAttribute('poster')||'',
            ready:typeof el.readyState==='number'?el.readyState:null
          })),
        watchLinks:[...document.querySelectorAll('a[href]')]
          .filter(el=>/\\/watch\\/|\\/video\\//i.test(el.getAttribute('href')||''))
          .length,
        cards:document.querySelectorAll('article,.video-card,.video-item,.video-box').length,
        // Diagnose the exact publisher DOM structure without saving video
        // URLs, account information, thumbnails, titles or media bytes.
        cardShapes:[...document.querySelectorAll('a[href*="/watch/"]')].slice(0,5)
          .map(a=>{
            const media=a.querySelector('video,img') ||
              a.parentElement?.querySelector('video,img') ||
              a.parentElement?.parentElement?.querySelector('video,img');
            const poster=media?.getAttribute('poster')||
              media?.getAttribute('src')||'';
            return {
              route:new URL(a.href,location.href).pathname,
              textLength:(a.innerText||'').trim().length,
              hasDirectImage:!!a.querySelector('img'),
              hasDirectVideo:!!a.querySelector('video'),
              parentTag:a.parentElement?.tagName||'',
              parentClass:(a.parentElement?.className||'').toString().slice(0,90),
              mediaTag:media?.tagName||'',
              mediaHost:(()=>{try{return new URL(poster,location.href).hostname}
                        catch(e){return ''}})()
            };
          }),
        title:document.title.slice(0,120),
        heading:(document.querySelector('h1')?.textContent||'').trim().slice(0,120),
        watchPaths:[...document.querySelectorAll('a[href]')].filter(el=>
            /^\/watch\/|^\/video\//i.test(new URL(el.href,location.href).pathname))
            .slice(0,15).map(el=>new URL(el.href,location.href).pathname),
        ageGate:/verify you are human|cloudflare|please enable javascript|access denied|blocked|captcha/i
            .test(document.body?.innerText?.slice(0,2000)||''),
        streamControls:[...document.querySelectorAll(
            'button,a,[role=button],input[type=button]')]
            .map(el=>(el.textContent||el.value||'').trim().slice(0,60))
            .filter(t=>/^stream|^play|^watch|^continue|^enter/i.test(t))
            .slice(0,30)
      }
    """)
    def host(u):
        return urllib.parse.urlsplit(u).hostname or ""
    urls=[{"tag":e["tag"],"host":host(e["src"]),
           "poster_host":host(e["poster"]),"ready":e.get("ready")}
           for e in values["players"]]
    iframe_shapes=sorted(set(
        re.sub(r'[A-Za-z0-9_-]{6,}', 'ID', urllib.parse.urlsplit(e["src"]).path)
        for e in values["players"] if e["tag"]=="IFRAME" and e["src"]
    ))[:8]
    out[name+" "+label]={"page_host":host(driver.current_url),
         "html_bytes":len(html.encode("utf-8")),"watch_links":values["watchLinks"],
         "cards":values["cards"],"player_elements":urls,
         "title":values["title"],"heading":values["heading"],
         "watch_paths":values["watchPaths"],"age_gate":values["ageGate"],
         "controls":values["streamControls"],"iframe_shapes":iframe_shapes,
         "card_shapes":values["cardShapes"]}
try:
    for name,url in SITES.items():
        try:
            driver.get(url)
            time.sleep(3)
            evidence(name,"initial")
            if name=="Archivebate":
                # Normal public 18+ entry control, no account creation/captcha.
                candidates=driver.find_elements(By.XPATH,
                    "//button[contains(translate(normalize-space(.), 'CONTINUE', 'continue'),'continue')]"
                    "|//a[contains(translate(normalize-space(.), 'CONTINUE', 'continue'),'continue')]")
                if candidates:
                    try:
                        driver.execute_script("arguments[0].click()",candidates[0])
                        time.sleep(3)
                        evidence(name,"after public continue")
                    except Exception as ex:
                        out[name+" continue"]={"error":str(ex)[:200]}
                # Open exactly one first-party public watch entry in the
                # ordinary browser. We do not bulk-download videos or alter
                # any account/cookie challenges. This checks real playback.
                initial=out.get(name+" after public continue") or out.get(name+" initial") or {}
                paths=initial.get("watch_paths",[])
                route=next((p for p in paths if re.match(r"^/watch/[a-z0-9][^/]{0,150}/?$",p,re.I)),None)
                if route:
                    driver.get(urllib.parse.urljoin(url,route))
                    time.sleep(4)
                    evidence(name,"one real watch page")
                    for video in driver.find_elements(By.CSS_SELECTOR,"video")[:1]:
                        try:
                            driver.execute_script("""
                                arguments[0].muted=true;
                                const promise=arguments[0].play();
                                if(promise&&typeof promise.catch==='function')
                                    promise.catch(()=>{});
                            """,video)
                            time.sleep(2)
                            evidence(name,"after video play")
                        except Exception as ex:
                            out[name+" play"]={"error":type(ex).__name__+":"+str(ex)[:160]}
                else:
                    out[name+" watch-route"]={"status":"no first-party watch route witnessed"}
            elif name=="Jav Guru":
                controls=driver.find_elements(By.XPATH,
                    "//*[self::button or self::a or @role='button']"
                    "[contains(translate(normalize-space(.), 'STREAM', 'stream'),'stream')]")
                # Only click genuine on-page stream chooser controls, never ads.
                attempted=0
                for control in controls[:8]:
                    label=(control.text or control.get_attribute("textContent") or "").strip()
                    if not re.match(r"(?i)^stream\s+(VO|LU|DD|JK)\b",label):
                        continue
                    try:
                        driver.execute_script("arguments[0].click()",control)
                        time.sleep(3)
                        attempted+=1
                        evidence(name,"stream "+str(attempted))
                        if attempted>=3:break
                    except Exception as ex:
                        out[name+" click "+str(attempted)]={"error":str(ex)[:200]}
                out[name+" choices"]={"observed_controls":len(controls),
                    "clicked_stream_choices":attempted}
                # A plain article HTTP 200 is not proof the player exists.
                # Collect the browser's own page title and bounded button
                # labels for diagnosis instead of claiming a stream was found.
        except Exception as ex:
            out[name]={"error":type(ex).__name__+": "+str(ex)[:300]}
    media=[]
    for event in driver.get_log("performance"):
        try:
            message=json.loads(event["message"])["message"]
            if message["method"]!="Network.responseReceived":continue
            data=message["params"]["response"]
            mime=data.get("mimeType","")
            url=data.get("url","")
            if mime.startswith("video/") or mime in (
                "application/vnd.apple.mpegurl","application/x-mpegurl",
                "application/dash+xml"):
                media.append({"status":data.get("status"),
                    "mime":mime,"host":urllib.parse.urlsplit(url).hostname})
        except Exception:pass
    out["observed_real_media_responses"]=media[:30]
finally:
    driver.quit()
with open("real-browser-player-results.json","w",encoding="utf8") as file:
    json.dump(out,file,indent=2,ensure_ascii=False)
for name,val in out.items():
    # Keep diagnostic video routes visible in CI logs, without exposing CDN
    # tokens, actual media URLs, or scene identifiers.
    short={k:val.get(k) for k in (
        "title","heading","watch_paths","iframe_shapes","age_gate","controls")
        if isinstance(val,dict) and k in val}
    print(name,json.dumps(short,ensure_ascii=False)[:1400])
    if name=="observed_real_media_responses":
        print("LIVE_MEDIA",json.dumps(val,ensure_ascii=False)[:450])
