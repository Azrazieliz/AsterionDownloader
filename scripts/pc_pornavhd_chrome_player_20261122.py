#!/usr/bin/env python3
"""Inspect a genuine published PornAVHD article and its UNIQUE selected
playerrecord iframe through normal headless Chrome. No ad click, CAPTCHA
bypass, media save or leaked signed source URL. Selected video network
responses/currentSrc/duration and HTTP codes are the only outputs.
"""
import html,json,re,shutil,sys,time,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
ROOT="https://pornavhd.com/"
ARTICLE=re.compile(r"^/20[0-9]{2}/(?:0?[1-9]|1[0-2])/(?:0?[1-9]|[12][0-9]|3[01])/[a-z0-9][a-z0-9_-]{2,220}/?$",re.I)
EMBED=re.compile(r"^/e/[A-Za-z0-9_-]{8,100}/?$")
class Links(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,tag,attrs):
  if tag=="a":
   d=dict(attrs)
   if d.get("href"):self.links.append(d["href"])
def public_page():
 req=urllib.request.Request(ROOT,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=22) as response:
  if response.status!=200:raise RuntimeError("publisher homepage access denied")
  htmlpage=response.read(900000).decode("utf8","replace")
 p=Links();p.feed(htmlpage)
 for candidate in p.links:
  uri=urllib.parse.urlsplit(urllib.parse.urljoin(ROOT,html.unescape(candidate)))
  if uri.scheme=="https" and uri.hostname in ("pornavhd.com","www.pornavhd.com") and ARTICLE.fullmatch(uri.path):
   return urllib.parse.urlunsplit((uri.scheme,uri.netloc,uri.path,"",""))
 raise RuntimeError("Publisher did not publish an exact selected recording")
def log(stage,**data):
 print(json.dumps({"publisher":"pornavhd","stage":stage,
    "complete_original":False,**data},sort_keys=True),flush=True)
def inspect(driver,seconds):
 state=None
 responses=[]
 last=0
 for tick in range(seconds):
  try:
   result=driver.execute_script("""return (function(){
    const media=[...document.querySelectorAll('video')].filter(e=>
      !e.closest('aside,.ads,.advertisement,.related,.sidebar'));
    const items=media.map(v=>{
      const u=v.currentSrc||v.getAttribute('src')||'';
      let host='',suffix='',query=false;
      try{const x=new URL(u);host=x.hostname;
        suffix=x.pathname.split('.').pop().slice(0,12);
        query=!!x.search;}catch(_){}
      return {has_src:!!u,host:host,extension:suffix,
        has_query:query,ready:v.readyState,network:v.networkState,
        duration:Number.isFinite(v.duration)?Math.round(v.duration):null,
        paused:v.paused};
    });
    let activated=false;
    if(items.length===0 || items.every(x=>x.ready<2)){
      const root=document.querySelector(
       '#player,.video-player,.player-container,.vjs-player');
      const btn=root?.querySelector(
       'button[aria-label="Play"],.vjs-big-play-button,'+
       '.plyr__control[data-plyr="play"],.fp-play,button.play-button');
      if(btn&&!btn.dataset.asterionPcClicked){
        btn.dataset.asterionPcClicked='true';btn.click();activated=true;
      }
    }
    return {media:items,activated:activated,
      title_len:document.title.length,
      nested_frames:[...document.querySelectorAll('iframe[src]')]
        .map(f=>{try{const u=new URL(f.src);
          return {host:u.hostname,route:u.pathname.split('/')[1]||''};}catch(_){return {}}})
        .slice(0,8)};
   })()""")
   if state!=result and (tick in (0,5,12,seconds-1) or result.get("activated")):
    log("SELECTED_BROWSER_MEDIA_STATE",elapsed=tick,**result)
   state=result
   for entry in driver.get_log("performance"):
    item=json.loads(entry["message"]).get("message",{})
    if item.get("method")!="Network.responseReceived":continue
    params=item.get("params",{});r=params.get("response",{})
    url=r.get("url","");u=urllib.parse.urlsplit(url)
    mime=r.get("mimeType","")
    kind=params.get("type","")
    if (kind=="Media" or mime.startswith("video/") or
       u.path.lower().endswith((".m3u8",".mp4",".ts",".m4s"))):
      responses.append({"host":u.hostname,"type":kind,
        "http":r.get("status"),"mime":mime[:50],
        "extension":u.path.rsplit(".",1)[-1][:10] if "." in u.path else "",
        "signed":bool(u.query)})
   if responses and (tick-last>=5 or tick==seconds-1):
    last=tick
    log("SELECTED_BROWSER_MEDIA_RESPONSES",
      total_observed=len(responses),recent=responses[-10:])
  except Exception as error:
   log("BROWSER_OBSERVATION_ERROR",error=type(error).__name__)
  time.sleep(1)
 return responses
def main():
 selected=public_page()
 opt=Options()
 opt.add_argument("--headless=new")
 opt.add_argument("--no-sandbox")
 opt.add_argument("--disable-dev-shm-usage")
 opt.add_argument("--disable-gpu")
 opt.add_argument("--window-size=412,915")
 opt.add_argument("--user-agent="+UA)
 opt.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 driver=webdriver.Chrome(options=opt)
 try:
  driver.set_page_load_timeout(32)
  driver.get(selected)
  frames=driver.find_elements(By.CSS_SELECTOR,"main iframe[src],article iframe[src]")
  valid=[]
  for frame in frames:
   src=frame.get_attribute("src") or ""
   u=urllib.parse.urlsplit(src)
   if u.scheme=="https" and u.hostname=="video.playerrecord.cfd" and EMBED.fullmatch(u.path) and not u.query and not u.fragment:
    valid.append(frame)
  log("CHROME_SELECTED_ARTICLE",article_host="pornavhd.com",
      iframe_total=len(frames),selected_player_frames=len(valid))
  if len(valid)!=1:raise RuntimeError("Selected iframe absent/ambiguous in Chrome")
  driver.switch_to.frame(valid[0])
  responses=inspect(driver,24)
  log("SELECTED_CHROME_SUMMARY",observed_media_responses=len(responses),
      no_full_file_receipt=True)
  return 0
 finally:driver.quit()
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  log("NOT_CERTIFIED",error_type=type(e).__name__,
      reason=re.sub(r"https?://[^\\s]+","[url]",str(e))[:190])
  sys.exit(2)
