#!/usr/bin/env python3
"""PC full-file acceptance on exact public selected articles, not catalog posts.
Only complete publisher-bound media with duration, bytes, tracks and SHA is PASS.
Uses the same ordinary public browser; does not defeat logins, CAPTCHAs or DRM.
Media is discarded immediately after validation; secrets/URLs with tokens omitted.
"""
import argparse,hashlib,json,re,subprocess,sys,tempfile,time,urllib.error,urllib.parse,urllib.request
from pathlib import Path
from bs4 import BeautifulSoup
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
FIXED={
 "javideo":"https://javideo.net/en/fc2ppv-4994603",
 "uncensoredhentai":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
 "avple":"https://avple.tv/video/1000101415221",
 "livecamrips":"https://livecamrips.to/",
 "pornavhd":"https://pornavhd.com/",
 "pornbuz":"https://pornbuz.com/video/promotional-pussy-ryan-reid-brazzers-93753/",
 "xchina":"https://en.xchina.co/",
 "dsav":"https://dsysav.com/"
}
def host(url):return (urllib.parse.urlsplit(url).hostname or "").lower()
def safe_url(url):
 p=urllib.parse.urlsplit(url)
 return p.scheme=="https" and p.hostname and not p.username and not p.password and p.port in (None,443)
def page(url,referer=None):
 headers={"User-Agent":UA,"Accept-Encoding":"identity"}
 if referer:headers["Referer"]=referer
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=16) as f:
   return f.status,f.url,f.read(1200000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:
  return e.code,e.url,e.read(16000).decode("utf-8","replace")
def driver():
 opt=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage","--disable-gpu","--mute-audio","--window-size=1280,850","--user-agent="+UA):
  opt.add_argument(flag)
 opt.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 d=webdriver.Chrome(options=opt);d.set_page_load_timeout(26);return d
def select(name,home,html):
 if name in ("javideo","uncensoredhentai","avple"):return home
 if name=="pornbuz" and re.fullmatch(r"/video/[a-z0-9][a-z0-9-]{7,190}-[0-9]{3,10}/?",urllib.parse.urlsplit(home).path,re.I):
  return home
 if name in ("livecamrips","pornavhd","dsav"):return None
 doc=BeautifulSoup(html,"html.parser")
 for a in doc.select("a[href]"):
  if a.find_parent(["aside"]) or a.find_parent(class_=re.compile("related|recommend|ad-",re.I)):continue
  u=urllib.parse.urljoin(home,a.get("href",""))
  if not safe_url(u):continue
  p=urllib.parse.urlsplit(u)
  if name=="pornbuz" and host(u) in ("pornbuz.com","www.pornbuz.com") and re.fullmatch(r"/video/[a-z0-9][a-z0-9-]{7,190}-[0-9]{3,10}/?",p.path,re.I):
   return urllib.parse.urlunsplit((p.scheme,p.netloc,p.path,"",""))
  if name=="xchina" and host(u) in ("xchina.co","en.xchina.co","www.xchina.co") and re.fullmatch(r"/video/(?:id-)?[a-f0-9]{10,32}(?:\.html)?/?",p.path,re.I):
   return urllib.parse.urlunsplit((p.scheme,p.netloc,p.path,"",""))
 return None
def frame_from_markup(name,article,html):
 soup=BeautifulSoup(html,"html.parser")
 matches=[]
 for iframe in soup.select("iframe[src]"):
  # Exact class tokens only: the actual selected player is nested under
  # class="video-player-area with-sidebar-ads". Matching the substring
  # "ads" wrongly threw away that genuine publisher-attested iframe.
  if iframe.find_parent(["aside"]):continue
  if any(any(c.lower() in {"ads","advertisement","ad","related","recommended","recommendations","sidebar"} or c.lower().startswith(("ad-","ads-")) for c in ancestor.get("class",[])) for ancestor in iframe.parents if hasattr(ancestor,"get")):continue
  u=urllib.parse.urljoin(article,iframe.get("src",""))
  if not safe_url(u):continue
  h=host(u);p=urllib.parse.urlsplit(u).path
  valid=(
   name=="javideo" and h=="play.javideo.net" and re.fullmatch(r"/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?",p) and iframe.find_parent(class_=re.compile("player|embed",re.I))
   or name=="uncensoredhentai" and h=="nhplayer.com" and re.fullmatch(r"/v/[A-Za-z0-9]{8,40}/?",p)
   or name=="pornbuz" and h in ("player.watchstreamus.com","cdn.watchstreamus.com") and re.fullmatch(r"/embed/[A-Za-z0-9_-]{5,150}/?",p)
   or name=="livecamrips" and iframe.get("id")=="iframe-video" and h in ("vidara.to","mixdrop.ag","mixdrop.co","mixdrop.to")
   )
  if valid:matches.append(u)
 return list(dict.fromkeys(matches))
def chrome_watch(name,url):
 d=driver()
 try:
  try:d.get(url)
  except Exception:pass  # Slow JS-loaded players can still have an inspectable DOM.
  out={"urls":[],"players":[],"manifest_hosts":[],"duration":[]}
  for tick in range(24):
   if name=="javideo":
    try:d.execute_script("""var b=document.querySelector('#clickfakeplayer');if(b&&!b.dataset.asterionPcClicked){b.dataset.asterionPcClicked='1';b.click()}""")
    except Exception:pass
   body=d.page_source
   for f in frame_from_markup(name,url,body):
    if f not in out["players"]:out["players"].append(f)
   try:
    for raw in d.execute_script("""return [...document.querySelectorAll('main video,article video,#player video,.video-player video')].map(v=>({url:v.currentSrc||v.src||'',duration:Number.isFinite(v.duration)?v.duration:0}));"""):
     u=raw.get("url","");dur=raw.get("duration",0)
     if safe_url(u) and u not in out["urls"]:out["urls"].append(u)
     if dur>=90:out["duration"].append(int(dur))
   except Exception:pass
   try:
    for evt in d.get_log("performance"):
     z=json.loads(evt["message"])["message"]
     if z.get("method")!="Network.responseReceived":continue
     res=z.get("params",{}).get("response",{})
     u=res.get("url","");mime=res.get("mimeType","")
     if safe_url(u) and (".m3u8" in urllib.parse.urlsplit(u).path or mime=="application/vnd.apple.mpegurl"):
      if host(u) not in out["manifest_hosts"]:out["manifest_hosts"].append(host(u))
   except Exception:pass
   if out["players"] and name in ("javideo","uncensoredhentai","pornbuz"):break
   time.sleep(.8)
  if name=="xchina":
   try:
    u=d.execute_script("""return document.querySelector('#video-player[data-src]')?.getAttribute('data-src')||''""")
    if u and safe_url(urllib.parse.urljoin(url,u)):out["urls"].append(urllib.parse.urljoin(url,u))
   except Exception:pass
  return out
 finally:d.quit()
def player_candidate(name,selected,frame):
 d=driver()
 try:
  # Navigate the publisher article first: embed cookies, parent referrer and
  # real iframe context cannot be reproduced by loading its player as top-level.
  try:d.get(selected)
  except Exception:pass
  if name=="javideo":
   try:d.execute_script("""const b=document.querySelector('#clickfakeplayer');
     if(b&&!b.closest('aside,.ads,.related,.recommended,[data-ad]'))b.click();""")
   except Exception:pass
  entered=False
  for attempt in range(20):
   try:
    for element in d.find_elements(By.CSS_SELECTOR,"iframe[src]"):
     u=element.get_attribute("src") or ""
     if u.rstrip("/")==frame.rstrip("/"):
      d.switch_to.frame(element)
      entered=True
      break
    if entered:break
   except Exception:pass
   time.sleep(.8)
  if not entered:
   return {"reason":"exact publisher player frame never loaded in article browser"}
  results=[]
  active_name=name
  active_referer=frame
  for tick in range(36):
   try:
    if active_name=="javideo":
     d.execute_script("""(function(){
      const all=[...document.querySelectorAll(
       '#player,.video-player,.player-container,.vjs-player')].filter(x=>
       !x.closest('aside,.related,.recommended,.ads,[data-ad]'));
      const roots=all.filter(x=>!all.some(y=>y!==x&&x.contains(y)));
      if(roots.length!==1)return;
      const root=roots[0];
      const button=root.querySelector(
       '.vjs-big-play-button,button[aria-label="Play"],button[title="Play"],'+
       'button.play-button,.plyr__control[data-plyr="play"]');
      if(button&&!button.dataset.pcSelectedClicked){
       button.dataset.pcSelectedClicked='yes';button.click();
      }
      const video=root.tagName==='VIDEO'?root:root.querySelector('video');
      if(video&&video.paused)try{const p=video.play();if(p?.catch)p.catch(()=>{})}catch(_){}
     })()""")
    got=d.execute_script("""return (function(){
     const h=location.hostname.toLowerCase(),p=location.pathname;
     if(h==='nhplayer.com' && /^\/v\/[A-Za-z0-9]{8,40}\/?$/.test(p)){
      const nodes=[...document.querySelectorAll(
       '.servers li[data-id],ul.servers li[data-id],#servers li[data-id]')];
      return {server:nodes.map(x=>x.getAttribute('data-id')),
       active:nodes.filter(x=>x.classList.contains('active') ||
        x.classList.contains('selected') || x.getAttribute('aria-selected')==='true')
        .map(x=>x.getAttribute('data-id'))};
     }
     if(h==='nhplayer.com' && p==='/player.php'){
      const media=typeof window.videoReady==='string'?window.videoReady:'';
      const vids=[...document.querySelectorAll('video')].map(v=>({
       url:v.currentSrc||v.src||'',duration:Number.isFinite(v.duration)?v.duration:0}));
      return {media,vids};
     }
     if(['player.watchstreamus.com','cdn.watchstreamus.com'].includes(h) &&
       /^\/embed\/[A-Za-z0-9_-]{5,150}\/?$/.test(p)){
      const source=window.VK_PLAYBACK?.format==='hls'?window.VK_PLAYBACK?.src:null;
      const vids=[...document.querySelectorAll('video')].map(v=>({
       url:v.currentSrc||v.src||'',duration:Number.isFinite(v.duration)?v.duration:0}));
      return {media:source,vids};
     }
     const vids=[...document.querySelectorAll('video')].map(v=>({
       url:v.currentSrc||v.src||'',duration:Number.isFinite(v.duration)?v.duration:0}));
     return {vids};
    })()""")
    if active_name=="uncensoredhentai" and got.get("server"):
     # Use only a single publisher-declared default/active /player.php.
     values=got["active"] or got["server"]
     urls=[urllib.parse.urljoin(frame,x) for x in values if x]
     urls=[u for u in urls if safe_url(u) and host(u)=="nhplayer.com" and
           urllib.parse.urlsplit(u).path=="/player.php" and
           set(urllib.parse.parse_qs(urllib.parse.urlsplit(u).query)) in
             ({"data"},{"i","s","vid"})]
     if len(set(urls))==1:
      server=urls[0]
      # Navigate the selected /v/ iframe to its own real published player.
      # Same browser/session, original first-party referrer and cookies retained.
      d.execute_script("location.assign(arguments[0])",server)
      active_name="nhplayer_server"
      active_referer=server
      time.sleep(1)
      continue
    if active_name in ("uncensoredhentai","nhplayer_server"):
     u=got.get("media","") or ""
     if (u and safe_url(u) and host(u)=="r2.1hanime.com" and
         urllib.parse.urlsplit(u).path.lower().endswith(".mp4") and
         set(urllib.parse.parse_qs(urllib.parse.urlsplit(u).query))=={"verify"}):
      return {"source":u,"referer":active_referer,"duration":0,
              "reason":"NHPlayer exact /v/ active server signed media"}
    if active_name=="pornbuz":
     u=got.get("media","") or ""
     if u:
      u=urllib.parse.urljoin(frame,u)
      if (safe_url(u) and host(u) in ("player.watchstreamus.com","cdn.watchstreamus.com") and
          urllib.parse.urlsplit(u).path.lower().endswith(".m3u8") and not
          re.search("preview|teaser|trailer|sample",urllib.parse.urlsplit(u).path,re.I)):
       return {"source":u,"referer":frame,"duration":0,
               "reason":"selected article's embedded JWPlayer VK_PLAYBACK"}
    for v in got.get("vids",[]):
     u=v.get("url","");duration=v.get("duration",0)
     if (u and safe_url(u) and duration>=90 and not
         re.search("preview|teaser|trailer|sample",urllib.parse.urlsplit(u).path,re.I)):
      results.append({"source":u,"referer":active_referer,"duration":int(duration),
                      "reason":"selected iframe HTML5 runtime"})
    unique={x["source"]:x for x in results}
    if len(unique)==1:return next(iter(unique.values()))
   except Exception:pass
   time.sleep(.8)
  return {"reason":"actual publisher iframe produced no uniquely attributable full media"}
 finally:d.quit()

def full_file(selected,candidate):
 url=candidate["source"]
 if not safe_url(url):raise ValueError("unsafe selected source")
 with tempfile.TemporaryDirectory(prefix="asterion-pc-selected-") as tmp:
  out=Path(tmp)
  cmd=[sys.executable,"-m","yt_dlp","--no-playlist","--no-part",
   "--no-mtime","--no-warnings","--retries","2","--fragment-retries","2",
   "--socket-timeout","14","--max-filesize","1200M","--format","bestvideo+bestaudio/best",
   "--add-header","Referer:"+candidate["referer"],"-o",str(out/"original.%(ext)s"),url]
  try:p=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=280)
  except subprocess.TimeoutExpired:raise RuntimeError("FULL_TRANSFER_TIMEOUT")
  if p.returncode:raise RuntimeError("EXTRACTOR_FAILED:"+p.stdout[-350:].replace(url,"[selected-source]"))
  files=[x for x in out.iterdir() if x.is_file() and x.stat().st_size>2_000_000 and not x.name.endswith(".part")]
  if len(files)!=1:raise RuntimeError("NO_SINGLE_COMPLETED_FILE")
  f=files[0]
  meta=json.loads(subprocess.check_output(["ffprobe","-v","error","-show_entries","format=duration,size:stream=codec_type","-of","json",str(f)],timeout=20,text=True))
  seconds=float(meta.get("format",{}).get("duration") or 0)
  tracks=[x["codec_type"] for x in meta.get("streams",[])]
  if seconds<90 or "video" not in tracks:raise RuntimeError("PREVIEW_OR_UNPLAYABLE")
  if candidate.get("duration",0)>=90 and abs(seconds-candidate["duration"])>max(10,candidate["duration"]*.1):raise RuntimeError("PLAYER_FILE_DURATION_MISMATCH")
  h=hashlib.sha256()
  with f.open("rb") as inp:
   for block in iter(lambda:inp.read(1048576),b""):h.update(block)
  return {"duration_seconds":round(seconds,3),"bytes":f.stat().st_size,"tracks":tracks,"sha256":h.hexdigest(),"complete_selected_original":True}
def check(name):
 start=time.monotonic();record={"provider":name,"complete_selected_original":False}
 home=FIXED[name]
 try:
  status,last,html=page(home);record["page_http"]=status
  if status!=200:raise RuntimeError("PUBLISHER_HTTP_"+str(status))
  selected=select(name,last,html)
  if not selected:
   record["reason"]="NO_EXACT_SELECTED_URL_OR_IDENTITY";return record
  record["selected_page_host"]=host(selected)
  status,final,html=page(selected)
  record["selected_http"]=status
  if status!=200:raise RuntimeError("SELECTED_HTTP_"+str(status))
  frames=frame_from_markup(name,selected,html)
  if name in ("javideo","uncensoredhentai","pornbuz","xchina"):
   browser=chrome_watch(name,selected)
   record["browser_player_hosts"]=[host(f) for f in browser["players"]]
   record["manifest_hosts"]=browser["manifest_hosts"]
   frames=list(dict.fromkeys(frames+browser["players"]))
  candidate=None
  if name=="xchina":
   soup=BeautifulSoup(html,"html.parser")
   arr=[x.get("data-src","") for x in soup.select("#video-player[data-src]")]
   arr+=browser["urls"]
   article_id=re.fullmatch(r"/video/(?:id-)?([a-f0-9]{10,32})(?:\.html)?/?",urllib.parse.urlsplit(selected).path,re.I)
   arr=[urllib.parse.urljoin(selected,x) for x in arr if x]
   arr=[x for x in arr if safe_url(x) and article_id and host(x) in ("xchina.co","en.xchina.co","www.xchina.co") and urllib.parse.urlsplit(x).path.lower()==("/hls/"+article_id[1]+"/master.m3u8").lower()]
   if len(set(arr))==1:candidate={"source":arr[0],"referer":selected,"duration":0,"reason":"same selected xChina article ID HLS"}
  elif name in ("javideo","uncensoredhentai","pornbuz"):
   record["player_count"]=len(frames)
   if len(frames)==1:candidate=player_candidate(name,selected,frames[0])
  if not candidate or "source" not in candidate:
   record["reason"]=(candidate or {}).get("reason","NO_SELECTED_ATTRIBUTABLE_MEDIA")
   return record
  record["selected_source_host"]=host(candidate["source"])
  record["source_attribution"]=candidate["reason"]
  record.update(full_file(selected,candidate))
 except Exception as e:record["reason"]=str(e)[:350]
 finally:record["elapsed_seconds"]=round(time.monotonic()-start,1)
 return record
if __name__=="__main__":
 p=argparse.ArgumentParser();p.add_argument("provider",choices=FIXED);args=p.parse_args()
 record=check(args.provider)
 output=Path("pc-full-"+args.provider+".json")
 output.write_text(json.dumps(record,indent=2,sort_keys=True))
 print(json.dumps(record,sort_keys=True),flush=True)
 if not record["complete_selected_original"]:sys.exit(2)
