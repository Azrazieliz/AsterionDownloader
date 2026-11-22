#!/usr/bin/env python3
"""Owner-requested DSYSAV fallback: public selected-article route discovery only.
Do not claim a completed video from an HTTP 200 catalog or a preview."""
import json,re,urllib.parse,urllib.request,urllib.error
from collections import Counter
from bs4 import BeautifulSoup
BASE="https://dsysav.com/en"
UA="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
def fetch(url):
 try:
  request=urllib.request.Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
  with urllib.request.urlopen(request,timeout=18) as response:
   return response.status,response.read(1_200_000).decode("utf8","replace"),response.url
 except urllib.error.HTTPError as error:return error.code,"",url
 except Exception as error:return 0,type(error).__name__,url
def internal(raw,base):
 try:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(base,raw))
  return u.geturl() if u.scheme=="https" and u.hostname in ("dsysav.com","www.dsysav.com") and not u.username and not u.password and u.port in (None,443) else None
 except Exception:return None
code,html,home=fetch(BASE)
out={"site":"DSYSAV","home_status":code,"full_file_certified":False}
if code==200:
 soup=BeautifulSoup(html,"html.parser")
 links=[]
 for a in soup.select("a[href]")[:1800]:
  target=internal(a.get("href",""),home)
  if target and target!=home and target not in links:links.append(target)
 out["internal_count"]=len(links)
 out["route_prefixes"]=dict(Counter((urllib.parse.urlsplit(u).path.strip("/").split("/")[0] or "/") for u in links).most_common(25))
 out["route_examples"]=[urllib.parse.urlsplit(u).path[:110] for u in links[:35]]
 candidates=[u for u in links if re.search(r"(?:video|play|vod|watch|detail|movie|film)",urllib.parse.urlsplit(u).path,re.I)]
 out["candidate_count"]=len(candidates)
 pages=[]
 for url in candidates[:3]:
  status,body,_=fetch(url)
  doc=BeautifulSoup(body,"html.parser")
  videos=[x.get("src") for x in doc.select("video[src],video source[src]") if x.get("src")]
  frames=[x.get("src") for x in doc.select("iframe[src]") if x.get("src")]
  pages.append({"path":urllib.parse.urlsplit(url).path[:90],"status":status,
    "frames":[urllib.parse.urlsplit(urllib.parse.urljoin(url,x)).hostname for x in frames[:8]],
    "media_hosts":[urllib.parse.urlsplit(urllib.parse.urljoin(url,x)).hostname for x in videos[:8]],
    "scripts":len(doc.select("script"))})
 out["selected_pages"]=pages
# DSYSAV public homepage is a JS-only shell: server response returned
# no first-party href at all. Inspect the same public site in real desktop
# Chrome before concluding there are no selected posts; do not click gates.
if code==200 and not out.get("candidate_count"):
 from selenium import webdriver
 from selenium.webdriver.chrome.options import Options
 from selenium.common.exceptions import TimeoutException
 import time
 options=Options()
 for flag in ("--headless=new","--no-sandbox","--disable-dev-shm-usage",
              "--disable-gpu","--window-size=1280,850"):
  options.add_argument(flag)
 browser=webdriver.Chrome(options=options)
 try:
  browser.set_page_load_timeout(17)
  try:browser.get(BASE)
  except TimeoutException:pass
  for wait in range(8):
   time.sleep(1)
   result=browser.execute_script("""return {
      title:document.title,
      links:[...document.querySelectorAll('a[href]')].slice(0,700)
        .map(a=>({href:a.getAttribute('href'),title:(a.textContent||'').trim().slice(0,50)})),
      frames:[...document.querySelectorAll('iframe[src]')].slice(0,20).map(f=>f.src),
      players:[...document.querySelectorAll('video,source[src]')].slice(0,10)
        .map(v=>({host:(()=>{try{return new URL(v.currentSrc||v.src).hostname}catch(_){return ''}})(),
           duration:Number.isFinite(v.duration)?v.duration:0})),
      bodyHint:(document.body?.innerText||'').slice(0,260),
      status:document.readyState};""")
   links=[]
   for row in result.get("links",[]):
    u=internal(row.get("href") or "",BASE)
    if u and u not in links:links.append(u)
   out["chrome_title"]=result.get("title","")[:100]
   external=[]
   for row in result.get("links",[]):
    raw=row.get("href") or ""
    try:
     href=urllib.parse.urlsplit(urllib.parse.urljoin(BASE,raw))
     if href.scheme=="https" and href.hostname not in ("dsysav.com","www.dsysav.com") and href.hostname:
      external.append({"host":href.hostname,"path":href.path[:85],
                       "label":(row.get("title") or "")[:50]})
    except Exception:pass
   out["chrome_external_links"]=external[:20]
   out["chrome_link_count"]=len(links)
   out["chrome_internal_route_prefixes"]=dict(Counter(
       urllib.parse.urlsplit(u).path.strip("/").split("/")[0] for u in links).most_common(20))
   out["chrome_route_examples"]=[urllib.parse.urlsplit(u).path[:105] for u in links[:25]]
   out["chrome_player_hosts"]=[v.get("host") for v in result.get("players",[]) if v.get("host")]
   out["chrome_frame_hosts"]=[urllib.parse.urlsplit(u).hostname for u in result.get("frames",[])]
   out["chrome_body_hint"]=result.get("bodyHint","")[:100]
   watch_links=[u for u in links if urllib.parse.urlsplit(u).path.rstrip("/") == "/en/watch"]
   out["chrome_watch_link_count"]=len(watch_links)
   out["chrome_watch_query_keys"]=[sorted(urllib.parse.parse_qs(urllib.parse.urlsplit(u).query)) for u in watch_links[:8]]
   out["chrome_watch_unique_urls"]=len(set(watch_links))
   if watch_links:break
 except Exception as error:out["chrome_error"]=type(error).__name__
 finally:browser.quit()

print(json.dumps(out,sort_keys=True,ensure_ascii=False),flush=True)
open("pc-dsysav-route-evidence.json","w").write(json.dumps(out,indent=2))
