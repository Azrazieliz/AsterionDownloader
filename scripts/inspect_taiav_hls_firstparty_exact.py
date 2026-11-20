#!/usr/bin/env python3
"""Read-only inspection of one existing TaiAV selected movie's published HLS.
No video download or new provider. Output short sanitized code contexts only.
"""
import re,json,urllib.request,urllib.parse
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Links(HTMLParser):
 def __init__(self):super().__init__();self.hrefs=[]
 def handle_starttag(self,t,a):
  if t=="a":
   u=dict(a).get("href")
   if u:self.hrefs.append(u)
def get(u):
 req=urllib.request.Request(u,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=20) as r:
  return r.url,r.read(1_800_000).decode("utf8","replace")
def mask(s):
 s=re.sub(r"https?(?:\\/\\/|://)[^\\s\"'<>{}]{1,350}",
          lambda x:"{URL_HOST:"+str(urllib.parse.urlsplit(x.group().replace("\\/","/")).hostname)+"}",s)
 s=re.sub(r"[A-Za-z0-9+/=_-]{42,}","{opaque}",s)
 return " ".join(s.split())[:2400]
def main():
 url,html=get("https://taiav.com/cn/")
 tags=Links();tags.feed(html)
 selected=[]
 for href in tags.hrefs:
  a=urllib.parse.urljoin(url,href)
  u=urllib.parse.urlsplit(a)
  if u.scheme=="https" and u.hostname in ("taiav.com","www.taiav.com") and re.fullmatch(r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?",u.path,re.I) and a not in selected:selected.append(a)
 if not selected:print(json.dumps({"error":"NO_SELECTED"}));return
 page,html=get(selected[0]);print(json.dumps({"selected_path":urllib.parse.urlsplit(page).path,"html_bytes":len(html)}),flush=True)
 position=html.lower().find("if(data.m3u8)")
 if position<0:position=html.lower().find("data.m3u8")
 ajax=html.rfind("$.ajax",0,position) if position>=0 else -1
 begin=ajax if ajax>=0 and position-ajax<2500 else max(0,position-1400)
 context=mask(html[begin:position+125]) if position>=0 else ""
 print(json.dumps({"stage":"TAIAV_SELECTED_FULL_HLS_AJAX",
    "hls_response_reference":position>=0,
    "nearby_ajax_request":ajax>=0 and position-ajax<2500,
    "context":context},ensure_ascii=True),flush=True)
if __name__=="__main__":main()
