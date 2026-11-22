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
  pornbuz_play_attempted=False
  for tick in range(24):
   if name=="pornbuz" and not pornbuz_play_attempted:
    # One normal Chromium user gesture on the exact selected main player.
    # Never click a sidebar, thumbnail, advertisement, login, or suggested video.
    try:
     controls=d.find_elements(By.CSS_SELECTOR,
       ".video-player-area .video-player .responsive-player "
       "button[aria-label='Play'], "
       ".video-player-area .video-player .responsive-player "
       "button[title='Play'], "
       ".video-player-area .video-player .responsive-player "
       ".vjs-big-play-button")
     controls=[c for c in controls if c.is_displayed() and c.is_enabled()]
     if len(controls)==1:
      controls[0].click()
      pornbuz_play_attempted=True
    except Exception:pass
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
  if name=="javideo":
   # The exact publisher film offers labelled alternate servers. Only one
   # active in-article STAPE#01 frame is permitted after normal Play failed;
   # a muted ad, related card, or guessed host never becomes a candidate.
   try:
    d.switch_to.default_content()
    buttons=[x for x in d.find_elements(By.XPATH,
      "//button[normalize-space(.)='STAPE#01']")
      if x.is_displayed() and x.is_enabled()]
    if len(buttons)==1:
     buttons[0].click()
     for _ in range(12):
      frames=d.find_elements(By.CSS_SELECTOR,
        ".embed-responsive.player:not(.d-none) iframe[src]")
      urls=list(dict.fromkeys(x.get_attribute("src") or "" for x in frames))
      valid=[]
      for u in urls:
       if not safe_url(u):continue
       p=urllib.parse.urlsplit(u)
       if (host(u) in ("streamtape.com","streamtape.to",
           "streamtape.net","streamtape.site","sbtape.com")
           and re.fullmatch(r"/(?:e|v|embed)/[A-Za-z0-9_-]{5,100}/?",p.path)
           and not p.query and not p.fragment):valid.append(u)
      if len(urls)==1 and len(valid)==1:
       # The installed yt-dlp has no StreamTape extractor. The /e/ HTML
       # page is not media. Inspect the real selected hoster frame in the
       # SAME ordinary browser and accept only its own long HTML5 media.
       chosen=valid[0]
       try:
        d.switch_to.frame(frames[0])
        clicked=False
        for _ in range(24):
         if not clicked:
          play=d.find_elements(By.CSS_SELECTOR,
            "#player .vjs-big-play-button,.video-player .vjs-big-play-button,"
            "button[aria-label='Play'],button[title='Play']")
          play=[v for v in play if v.is_displayed() and v.is_enabled()]
          if len(play)==1:
           try:play[0].click();clicked=True
           except Exception:pass
         # Streamtape's selected public player may publish its ordinary
         # /get_video control in #robotlink instead of video.currentSrc.
         # Read only the selected iframe DOM, never other player requests.
         published=d.execute_script("""return [...document.querySelectorAll(
           '#robotlink,#norobotlink,#videolink,#ideoolink,#ideoooolink')]
           .map(x=>x.textContent.trim())
           .filter(x=>x.includes('/get_video?')).slice(0,5);""")
         media_urls=[]
         for raw in published:
          raw=raw.strip()
          if raw.startswith('//'):raw='https:'+raw
          elif raw.startswith('/'):raw=urllib.parse.urljoin(chosen,raw)
          if not safe_url(raw):continue
          uri=urllib.parse.urlsplit(raw)
          params=urllib.parse.parse_qs(uri.query,keep_blank_values=True)
          frame_id=urllib.parse.urlsplit(chosen).path.strip('/').split('/')[1]
          if (host(raw) in ('streamtape.com','streamtape.to',
              'streamtape.net','streamtape.site','sbtape.com')
              and uri.path=='/get_video' and uri.fragment==''
              and len(params.get('id',[]))==1
              and params['id'][0]==frame_id
              and all(k in ('id','expires','ip','token','stream','dl') for k in params)):
           media_urls.append(raw)
         if len(set(media_urls))==1:
          # Current Streamtape player scripts publish a required signed
          # &token= alongside #ideoooolink. The DOM's incomplete bait
          # link often stalls. Only append the first-party script's token;
          # never synthesize it, decode challenges or borrow other frames.
          script_texts=d.execute_script("""return [...document.querySelectorAll('script:not([src])')]
            .map(x=>x.textContent||'').filter(x=>x.includes('ideoooolink')&&x.includes('&token='))
            .slice(0,10);""") or []
          tokens=set()
          for script_text in script_texts:
           tokens.update(re.findall(r"&token=([A-Za-z0-9_-]{4,160})(?=['\\\"&\\s]|$)",script_text))
          selected_media=media_urls[0]
          query=urllib.parse.parse_qs(urllib.parse.urlsplit(selected_media).query)
          if len(tokens)==1 and not query.get('token'):
           selected_media=selected_media+('&' if urllib.parse.urlsplit(selected_media).query else '?')+'token='+next(iter(tokens))
          return {'source':selected_media,'referer':chosen,'duration':0,
            'reason':'same selected JAVIDEO film - STAPE#01 published token-bearing original',
            'signed_token_attested':len(tokens)==1}
         current=d.execute_script("""return [...document.querySelectorAll('video')]
           .filter(v=>!v.closest('aside,.related,.recommendations,.ads,'+
             '.advertisement,.sidebar,[data-ad]'))
           .map(v=>({src:v.currentSrc||v.src||'',
             duration:Number.isFinite(v.duration)?v.duration:0}));""")
         candidates={}
         for video in current:
          media=video.get("src","");runtime=video.get("duration",0)
          if not safe_url(media) or runtime<600:continue
          uri=urllib.parse.urlsplit(media)
          if not re.search(r"(?i)\.(?:mp4|m3u8)$",uri.path):continue
          if re.search(r"(?:^|[/._-])(?:preview|teaser|trailer|sample|ad|ads)(?:[/._-]|$)",uri.path,re.I):continue
          candidates[media]=int(runtime)
         if len(candidates)==1:
          media,seconds=next(iter(candidates.items()))
          return {"source":media,"referer":chosen,"duration":seconds,
            "reason":"same selected JAVIDEO film - STAPE#01 active long HTML5 video"}
         time.sleep(.6)
       except Exception:pass
       finally:d.switch_to.default_content()
       return {"reason":"selected STAPE#01 iframe did not publish a unique long media file"}
      time.sleep(.6)
   except Exception:pass
  return {"reason":"actual publisher iframe produced no uniquely attributable full media"}
 finally:d.quit()

def full_file(selected,candidate):
 url=candidate["source"]
 if not safe_url(url):raise ValueError("unsafe selected source")
 with tempfile.TemporaryDirectory(prefix="asterion-pc-selected-") as tmp:
  out=Path(tmp)
  if urllib.parse.urlsplit(url).path=="/get_video":
   # Exact publisher-selected STAPE source only. Complete contiguous HTTP
   # ranges are proof; HTML, 200-on-Range and partial 206 are never originals.
   from concurrent.futures import ThreadPoolExecutor
   headers={"User-Agent":UA,"Referer":candidate["referer"],"Accept-Encoding":"identity"}
   media_types=("video/mp4","application/octet-stream","binary/octet-stream")
   deadline=time.monotonic()+610

   def verify(response):
    u=urllib.parse.urlsplit(response.url)
    if u.scheme!="https" or not u.hostname or u.username or u.password:
     raise RuntimeError("HOSTER_UNSAFE_REDIRECT")
    mime=response.headers.get("Content-Type","").lower().split(";")[0].strip()
    if mime not in media_types:
     raise RuntimeError("HOSTER_NONMEDIA_"+mime[:40])

   def size_limit(n):
    if n<=2_000_000 or n>1_200_000_000:
     raise RuntimeError("HOSTER_NOT_FULL_FILE_LENGTH_"+str(n))
    return n

   with urllib.request.urlopen(urllib.request.Request(url,
       headers=dict(headers,Range="bytes=0-0")),timeout=27) as probe:
    verify(probe)
    status=probe.status
    cr=probe.headers.get("Content-Range","").strip()
    m=re.fullmatch(r"bytes 0-0/([0-9]+)",cr)
    ranges=status==206 and m is not None
    total=size_limit(int(m[1]) if ranges else
        int(probe.headers.get("Content-Length") or 0))
    if status not in (200,206) or (status==206 and not ranges):
     raise RuntimeError("HOSTER_INVALID_RANGE_PROBE")

   print(json.dumps({"provider":"javideo","stage":"ATTESTED_SELECTED_GET_VIDEO","expected_total_bytes":total,
      "range_supported":ranges,"signed_token_attested":candidate.get("signed_token_attested",False)}),flush=True)
   dest=out/"original.mp4"
   if ranges:
    # Four independently validated non-overlapping ranges prevent one
    # throttled multi-hundred-MiB response from monopolizing the transfer.
    # Exact byte positions, total length and final SHA remain mandatory.
    pieces=out/"pieces";pieces.mkdir()
    partitions=4
    boundaries=[(total*i)//partitions for i in range(partitions+1)]
    def lane(index,start,end):
     expected=end-start+1
     request=urllib.request.Request(url,
         headers=dict(headers,Range="bytes="+str(start)+"-"+str(end)))
     with urllib.request.urlopen(request,timeout=30) as response:
      verify(response)
      expected_cr="bytes "+str(start)+"-"+str(end)+"/"+str(total)
      if response.status!=206 or response.headers.get("Content-Range","").strip()!=expected_cr:
       raise RuntimeError("HOSTER_RANGE_MISMATCH_"+str(index))
      part_size=response.headers.get("Content-Length")
      if part_size and int(part_size)!=expected:
       raise RuntimeError("HOSTER_RANGE_LENGTH_MISMATCH_"+str(index))
      part=pieces/(str(index)+".part")
      received=0
      with part.open("wb") as output:
       while received<expected:
        if time.monotonic()>deadline:raise RuntimeError("HOSTER_RANGE_TIMEOUT_"+str(index)+"_"+str(received)+"_of_"+str(expected))
        block=response.read(min(1048576,expected-received))
        if not block:break
        output.write(block);received+=len(block)
      if received!=expected or response.read(1):
       raise RuntimeError("HOSTER_RANGE_TRUNCATED_OR_OVERLONG_"+str(index)+"_"+str(received))
      return part
    with ThreadPoolExecutor(max_workers=partitions) as workers:
     futures=[workers.submit(lane,i,boundaries[i],boundaries[i+1]-1)
       for i in range(partitions)]
     parts=[future.result() for future in futures]
    with dest.open("wb") as output:
     for part in parts:
      with part.open("rb") as incoming:
       for block in iter(lambda:incoming.read(1048576),b""):
        output.write(block)
      part.unlink()
    pieces.rmdir()
   else:
    request=urllib.request.Request(url,headers=headers)
    with urllib.request.urlopen(request,timeout=30) as response:
     verify(response)
     if response.status!=200:
      raise RuntimeError("HOSTER_REJECTED_COMPLETE_STREAM")
     expected=size_limit(int(response.headers.get("Content-Length") or 0))
     if expected!=total:
      raise RuntimeError("HOSTER_ORIGINAL_CHANGED_AFTER_PROBE")
     received=0
     with dest.open("wb") as output:
      while received<total:
       if time.monotonic()>deadline:
        raise RuntimeError("FULL_TRANSFER_TIMEOUT_"+str(received)+"_of_"+str(total))
       block=response.read(min(1048576,total-received))
       if not block:break
       output.write(block);received+=len(block)
     if received!=total or response.read(1):
      raise RuntimeError("INCOMPLETE_HOSTER_BYTES_"+str(received))
   if dest.stat().st_size!=total:
    raise RuntimeError("FINAL_HOSTER_BYTE_COUNT_MISMATCH")
  else:
   cmd=[sys.executable,"-m","yt_dlp","--no-playlist","--no-part",
    "--no-mtime","--no-warnings","--retries","2","--fragment-retries","2",
    "--abort-on-unavailable-fragments",
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
  if seconds<90 or "video" not in tracks or "audio" not in tracks:raise RuntimeError("PREVIEW_OR_UNPLAYABLE")
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
