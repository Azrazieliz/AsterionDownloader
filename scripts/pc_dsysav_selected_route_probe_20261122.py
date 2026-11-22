#!/usr/bin/env python3
"""Owner-requested DSYSAV fallback: public selected-article route discovery only.
Do not claim a completed video from an HTTP 200 catalog or a preview."""
import json,re,urllib.parse,urllib.request,urllib.error
from collections import Counter
from bs4 import BeautifulSoup
BASE="https://dsysav.com/"
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
print(json.dumps(out,sort_keys=True,ensure_ascii=False),flush=True)
open("pc-dsysav-route-evidence.json","w").write(json.dumps(out,indent=2))
