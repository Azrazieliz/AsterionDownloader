#!/usr/bin/env python3
"""Look ONLY for alternatives in the owner's exact HelloPorn article.

The selected HelloPorn page has one VOE iframe, which is refused on the
owner's French PC. This diagnostic inventories *publisher-supplied* alternate
player/download/media links in same selected article HTML, not a domain
guess, search result, recommendation or CAPTCHA workaround. No media is
downloaded, no URL tokens or file IDs are printed.
"""
import json,re,sys,urllib.parse,urllib.request
from html.parser import HTMLParser
from verify_helloporn_selected_voe_media import ARTICLE,UA
MEDIA=r"\.(?:m3u8|mp4|mpd|webm|mov|mkv)(?:\?|$)"
class Page(HTMLParser):
 def __init__(self):super().__init__();self.tags=[]
 def handle_starttag(self,tag,attrs):
  if tag not in ("iframe","video","source","a","button","div","script","input"):
   return
  a=dict(attrs)
  candidates=[]
  for key,val in a.items():
   if not isinstance(val,str):continue
   if key.lower() in ("href","src","data-src","data-video","data-url",
        "data-file","data-player","data-embed","data-download"):
    candidates.append((key,val))
  self.tags.append((tag,candidates,(a.get("id","")+" "+a.get("class",""))[:80]))
def main():
 req=urllib.request.Request(ARTICLE,headers={"User-Agent":UA,
    "Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=26) as resp:
  code=resp.status;raw=resp.read(1_000_000).decode("utf8","replace")
 if code!=200:raise RuntimeError("Selected article unavailable")
 parser=Page();parser.feed(raw)
 shapes=[]
 for tag,attrs,context in parser.tags[:3500]:
  for attr,val in attrs:
   if not val.startswith(("http://","https://","//","/")):continue
   u=urllib.parse.urlsplit(urllib.parse.urljoin(ARTICLE,val))
   if u.scheme!="https" or not u.hostname or u.username or u.password:
    continue
   if(tag=="a" and not re.search(
       r"download|player|original|video|stream|source",context,re.I)):
    continue
   kind=("media" if re.search(MEDIA,u.path,re.I) else
      "player" if re.search(r"/(?:e|embed|player|watch)/",u.path,re.I)
      else "other")
   if kind=="other":continue
   shapes.append({"tag":tag,"attribute":attr,"host":u.hostname,
      "route_kind":kind,"file_ext":u.path.rsplit(".",1)[-1][:7]
         if kind=="media" else None,
      "third_party":u.hostname not in ("helloporn.com","www.helloporn.com"),
      "context_player":bool(re.search(r"player|video|embed",context,re.I))})
 # First-party JS may encode selected media in JSON even if there is no
 # HTML <video>. Count media extensions without accepting ad sources.
 script_media=re.findall(r"https?:\\?/\\?/[^\"'\\s<>]{10,2200}",raw)
 media_hosts=[]
 for hit in script_media[:1000]:
  clean=hit.replace("\\/","/")
  if not re.search(MEDIA,clean,re.I):continue
  u=urllib.parse.urlsplit(clean)
  if u.scheme=="https" and u.hostname:media_hosts.append(u.hostname)
 print(json.dumps({"provider":"helloporn",
   "stage":"EXACT_SELECTED_FIRST_PARTY_ALTERNATE_MEDIA_INVENTORY",
   "article_http":code,"html_bytes":len(raw),
   "published_candidate_shapes":shapes[:45],
   "direct_media_host_counts":{h:media_hosts.count(h)
      for h in sorted(set(media_hosts))[:30]},
   "inline_media_extension_occurrences":{
     ext:len(re.findall(r"\."+ext+r"(?:\?|[\"'\\s])",raw,re.I))
     for ext in ("m3u8","mp4","mpd","webm")},
   "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"helloporn",
    "status":"FIRST_PARTY_ALTERNATIVE_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(3)
