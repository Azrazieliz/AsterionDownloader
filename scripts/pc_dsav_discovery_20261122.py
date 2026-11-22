#!/usr/bin/env python3
"""Discover actual public DSAV publisher article URLs and player contracts.
No guessed media URLs, private sessions, CAPTCHA bypass, or file downloads.
Only publicly rendered first-party links and media-host/status observations.
"""
import collections,json,re,time,urllib.parse,urllib.request,urllib.error
from bs4 import BeautifulSoup
from selenium import webdriver
from selenium.webdriver.chrome.options import Options

ROOT="https://dsav123.wiki/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
def valid(link):
    p=urllib.parse.urlsplit(link)
    return p.scheme=="https" and p.hostname in {"dsav123.wiki","www.dsav123.wiki"} and not p.username and not p.password and p.port in (None,443)
def route(link):
    p=urllib.parse.urlsplit(link)
    return p.path[:170] or "/"
def links_from_html(body,base):
    soup=BeautifulSoup(body,"html.parser")
    return list(dict.fromkeys(urllib.parse.urljoin(base,a.get("href","")) for a in soup.select("a[href]") if valid(urllib.parse.urljoin(base,a.get("href","")))))[:350]
def report(phase,**data):
    print(json.dumps(dict(phase=phase,**data),sort_keys=True),flush=True)
headers={"User-Agent":UA,"Accept-Encoding":"identity"}
with urllib.request.urlopen(urllib.request.Request(ROOT,headers=headers),timeout=20) as response:
    assert response.status==200
    html=response.read(2_000_000).decode("utf-8","replace")
ssr=links_from_html(html,ROOT)
report("DSAV_PUBLIC_HTML",html_bytes=len(html),same_site_links=len(ssr),
    routes=[route(v) for v in ssr[:30]],
    next_data="__NEXT_DATA__" in html,
    video_tags=len(re.findall(r"<video\b",html,re.I)))
opt=Options()
for arg in ["--headless=new","--no-sandbox","--disable-dev-shm-usage","--disable-gpu",
            "--window-size=1300,900","--user-agent="+UA]:
    opt.add_argument(arg)
opt.set_capability("goog:loggingPrefs",{"performance":"ALL"})
driver=webdriver.Chrome(options=opt)
try:
    driver.set_page_load_timeout(22)
    try:driver.get(ROOT)
    except Exception:pass
    state={}
    for _ in range(10):
        time.sleep(.8)
        state=driver.execute_script("""return {
            title:document.title,
            urls:[...document.querySelectorAll('a[href]')].map(a=>a.href).filter(Boolean).slice(0,400),
            media:[...document.querySelectorAll('video')].map(v=>({hasSrc:!!(v.currentSrc||v.src),duration:Number.isFinite(v.duration)?Math.round(v.duration):0})).slice(0,10),
            clickable:document.querySelectorAll('[role=link],[onclick]').length,
            nextRoute:window.__NEXT_DATA__?.page||null,
            htmlSize:document.documentElement.outerHTML.length
        }""")
        if len(state.get("urls",[]))>10:break
    selected=[x for x in state.get("urls",[]) if valid(x)]
    routes=list(dict.fromkeys(route(v) for v in selected))
    report("DSAV_BROWSER_ROUTES",title=state.get("title"),routes=routes[:90],
        href_count=len(selected),media=state.get("media"),
        clickable=state.get("clickable"),next_route=state.get("nextRoute"))
    candidates=[]
    for u in selected:
        p=urllib.parse.urlsplit(u)
        if re.search(r"/(?:video|videos|watch|play|player|detail|movie|vod|v)/[^/?#]{4,140}",p.path,re.I):
            candidates.append(u)
    if not candidates:
        for u in selected:
            path=urllib.parse.urlsplit(u).path
            if path.count("/")>=2 and not re.search(r"/(?:category|tag|actor|search|list|ranking|login|register|user|profile|home)(?:/|$)",path,re.I):
                candidates.append(u)
    if candidates:
        selected_article=candidates[0]
        try:driver.get(selected_article)
        except Exception:pass
        time.sleep(4)
        rendered=driver.execute_script("""return {
            title:document.title,
            frames:[...document.querySelectorAll('main iframe[src],article iframe[src],#player iframe[src]')].map(x=>x.src).slice(0,10),
            videos:[...document.querySelectorAll('main video,article video,#player video')].map(v=>({src:v.currentSrc||v.src||'',seconds:Number.isFinite(v.duration)?Math.round(v.duration):0})).slice(0,10),
            route:location.pathname,
            hasPlayButton:!!document.querySelector('button[aria-label*=Play],.vjs-big-play-button,.jw-icon-playback')
        }""")
        media=[]
        for entry in driver.get_log("performance"):
            try:
                event=json.loads(entry["message"])["message"]
                if event.get("method")!="Network.responseReceived":continue
                res=event.get("params",{}).get("response",{})
                u=res.get("url","")
                mime=res.get("mimeType","")
                parsed=urllib.parse.urlsplit(u)
                if parsed.scheme=="https" and ("video/" in mime or parsed.path.endswith((".m3u8",".mp4",".mpd"))):
                    media.append({"host":parsed.hostname,"mime":mime[:70],"http":int(res.get("status",0))})
            except Exception:pass
        report("DSAV_SELECTED_PAGE",selected_article_path=route(selected_article),
            selected_document_path=rendered.get("route"),
            video_sources=[{"host":urllib.parse.urlsplit(x.get("src","")).hostname,
                "seconds":x.get("seconds",0)} for x in rendered.get("videos",[])],
            frames=[{"host":urllib.parse.urlsplit(x).hostname,
                "path":urllib.parse.urlsplit(x).path[:65]} for x in rendered.get("frames",[])],
            has_play_button=rendered.get("hasPlayButton"),media_responses=media[:22])
    else:report("DSAV_SELECTED_PAGE",result="NO_PUBLIC_SELECTABLE_ARTICLE")
finally:
    driver.quit()
