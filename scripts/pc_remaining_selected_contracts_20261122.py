#!/usr/bin/env python3
"""Independent PC checks of all still-uncertified selected publisher flows.
Records network/player evidence, never labels a selected watch page or an
HTTP range sample a completed transfer. Uses only publisher-selected frames,
normal public Chrome/HTTP, no CAPTCHA solver, no guessed signed media URLs.
"""
import html,json,re,sys,time,urllib.error,urllib.parse,urllib.request
from html.parser import HTMLParser
from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.common.by import By

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
TARGETS={
 "javideo":{"url":"https://javideo.net/en/fc2ppv-4994603","frame_hosts":["play.javideo.net","streamtape.com","streamtape.to"]},
 "uncensoredhentai":{"url":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/","frame_hosts":["nhplayer.com"]},
 "avple":{"url":"https://avple.tv/video/1000101415221","frame_hosts":[]},
 "pornbuz":{"url":"https://pornbuz.com/","frame_hosts":["player.watchstreamus.com","cdn.watchstreamus.com","streamtape.com"]},
 "xchina":{"url":"https://en.xchina.co/","frame_hosts":[]},
 "livecamrips":{"url":"https://livecamrips.to/","frame_hosts":["vidara.to","mixdrop.ag"]}
}
class Markup(HTMLParser):
 def __init__(self):super().__init__();self.anchors=[];self.frames=[];self.videos=[];self.scope=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.anchors.append((a["href"]," ".join(self.scope[-7:])))
  if tag=="iframe" and a.get("src"):self.frames.append((a["src"]," ".join(self.scope[-7:])))
  if tag in ("video","source") and a.get("src"):self.videos.append((a["src"]," ".join(self.scope[-7:])))
  if tag not in ("img","source","meta","link","br","hr","input","wbr"):
   self.scope.append(tag+"#"+a.get("id","")+"."+a.get("class",""))
 def handle_endtag(self,tag):
  for ix in range(len(self.scope)-1,-1,-1):
   if self.scope[ix].split("#")[0]==tag:
    del self.scope[ix:];break
def get(url,ref=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml"}
 if ref:h["Referer"]=ref
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=22) as res:
   return res.status,res.url,res.read(1_100_000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:
  return e.code,e.url,e.read(16000).decode("utf8","replace")
def watch_path(name,path):
 if name=="pornbuz":return bool(re.fullmatch(r"/[a-z0-9][a-z0-9_-]{8,220}/?",path,re.I) and path.count("/")<=2)
 if name=="xchina":return bool(re.fullmatch(r"/video/[A-Za-z0-9_-]{6,160}/?",path))
 if name=="livecamrips":return bool(re.fullmatch(r"/video/[0-9]{4,15}/?",path))
 return False
def choose_watch(name,listing,body):
 markup=Markup();markup.feed(body)
 links=[]
 for raw,scope in markup.anchors:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(listing,html.unescape(raw)))
  if u.scheme!="https" or u.hostname not in {
    urllib.parse.urlsplit(listing).hostname,
    "www."+urllib.parse.urlsplit(listing).hostname.removeprefix("www.")}
    :continue
  if watch_path(name,u.path) and "aside#" not in scope:
   links.append(urllib.parse.urlunsplit((u.scheme,u.netloc,u.path,"","")))
 return list(dict.fromkeys(links))
def log(**item):
 print(json.dumps(item,sort_keys=True),flush=True)
def selected_frame(name,page,body):
 parser=Markup();parser.feed(body)
 valid=[]
 for raw,scope in parser.frames:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(page,html.unescape(raw)))
  if(u.scheme=="https" and u.hostname in TARGETS[name]["frame_hosts"] and
      "aside#" not in scope and ".related" not in scope and ".ads" not in scope):
   valid.append((u.geturl(),u.hostname))
 return list(dict.fromkeys(valid)),len(parser.frames),len(parser.videos)
def chrome_probe(name,selected):
 opt=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage","--disable-gpu",
              "--window-size=412,915","--user-agent="+UA):
  opt.add_argument(flag)
 opt.set_capability("goog:loggingPrefs",{"performance":"ALL"})
 driver=webdriver.Chrome(options=opt)
 found={};frames=set();reported={}
 try:
  driver.set_page_load_timeout(25)
  driver.get(selected)
  for second in range(18):
   try:
    reported=driver.execute_script("""return (function(){
      if(location.hostname==='javideo.net'){
       const button=document.querySelector('#clickfakeplayer');
       if(button&&!button.dataset.asterionChecked){
        button.dataset.asterionChecked='true';button.click();
       }
      }
      const frames=[...document.querySelectorAll(
       '#player iframe[src],main iframe[src],article iframe[src],'+
       '.video-player iframe[src],.embed-responsive.player:not(.d-none) iframe[src]')];
      const sources=[...document.querySelectorAll('main video,article video,#player video')]
       .map(v=>({ready:v.readyState,duration:Number.isFinite(v.duration)?
         Math.round(v.duration):null,source_host:(()=>{
           try{return new URL(v.currentSrc||v.src).hostname}catch(_){return ''}
         })()}));
      return {selected_frames:frames.map(f=>{try{return new URL(f.src).hostname}catch(_){return ''}}).slice(0,8),
       video_sources:sources.slice(0,5)};
    })()""")
    frames.update(reported.get("selected_frames",[]))
    for item in driver.get_log("performance"):
     try:
      data=json.loads(item["message"])["message"]
      if data.get("method")!="Network.responseReceived":continue
      p=data.get("params",{});res=p.get("response",{})
      url=urllib.parse.urlsplit(res.get("url",""))
      typ=p.get("type","");mime=res.get("mimeType","")
      if typ not in ("Media","XHR","Fetch") and not(
          "video/" in mime or url.path.lower().endswith((".mp4",".m3u8",".mpd"))):continue
      if url.scheme!="https":continue
      k=(url.hostname,int(res.get("status") or 0),mime.split(";")[0],typ)
      found[k]=found.get(k,0)+1
     except Exception:continue
   except Exception as e:
    reported={"error":type(e).__name__}
   time.sleep(1)
  return {"browser":"rendered","iframe_hosts":sorted(frames),
          "selected_video_dom":reported.get("video_sources",[]),
          "media_responses":[{"host":h,"http":code,"mime":mime,"kind":typ,"count":count}
            for (h,code,mime,typ),count in list(found.items())[:18]]}
 finally:
  driver.quit()
def run_case(name,config):
 selected=config["url"];kind="exact_test_selected" if name in ("javideo","uncensoredhentai","avple") else "public_discovered"
 try:
  code,land,body=get(selected)
  if code==200 and kind=="public_discovered":
   options=choose_watch(name,land,body)
   if options:
    selected=options[0]
    code,land,body=get(selected,config["url"])
   else:
    kind="catalog_only_no_selected_article"
  if code!=200:
   return {"provider":name,"case":kind,"selected_http":code,"result":"ACCESS_BLOCKED",
     "full_transfer_certified":False}
  frames,total,media=selected_frame(name,land,body)
  result={"provider":name,"case":kind,"selected_http":code,
   "selected_host":urllib.parse.urlsplit(selected).hostname,
   "selected_frames":[{"host":host,"path_kind":urllib.parse.urlsplit(url).path.split("/")[1]}
       for url,host in frames[:4]],"other_iframes":total,
   "inline_video_elements":media,"full_transfer_certified":False}
  if name in ("javideo","uncensoredhentai","pornbuz","xchina") and kind!="catalog_only_no_selected_article":
   try:result.update(chrome_probe(name,selected))
   except Exception as error:result["browser_error"]=type(error).__name__
  # Pure page/iframe discovery cannot be promoted to whole-file success.
  result["result"]=("PLAYER_EVIDENCE_NOT_WHOLE_FILE" if frames or media or
                      result.get("media_responses") else "NO_SELECTED_MEDIA")
  return result
 except Exception as error:
  return {"provider":name,"case":kind,
   "result":"NETWORK_OR_BROWSER_FAILURE","error_type":type(error).__name__,
   "full_transfer_certified":False}
if __name__=="__main__":
 records=[run_case(k,v) for k,v in TARGETS.items()]
 for row in records:log(**row)
 with open("pc-selected-provider-matrix-20261122.json","w") as f:
  json.dump(records,f,indent=2)
 # Zero successful full originals is a truthful outcome, not a script failure;
 # errors are still individually visible and acceptance cannot pass them.
