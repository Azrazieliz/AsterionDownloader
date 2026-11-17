#!/usr/bin/env python3
"""Proof-of-source: selected Pornhub viewkey -> one first-party flashvars
object -> selected HLS mediaDefinitions -> actual #EXTM3U response.

No unrelated page recommendations, guessed playlist, DRM, CAPTCHA or private
account access. Never print signed media URLs or expiring query parameters.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
HOME="https://www.pornhub.com/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Links(HTMLParser):
 def __init__(self):super().__init__();self.links=[]
 def handle_starttag(self,t,a):
  v=dict(a)
  if t=="a" and v.get("href"):self.links.append(v["href"])
def request(url,ref=None):
 headers={"User-Agent":UA,"Accept-Encoding":"identity","Accept-Language":"en-US,en;q=0.9"}
 if ref:headers["Referer"]=ref
 return urllib.request.Request(url,headers=headers)
def get(url,ref=None):
 with urllib.request.urlopen(request(url,ref),timeout=23) as r:
  return r.status,r.url,r.headers.get("Content-Type","").split(";")[0],r.read(1_600_000)
def extract(body):
 configs=[]
 for match in re.finditer(r"(?:\bvar\s+)?\b(flashvars_[A-Za-z0-9_]{1,72})\s*=\s*",body):
  try:
   obj,_=json.JSONDecoder().raw_decode(body[match.end():].lstrip())
  except Exception:continue
  if not isinstance(obj,dict) or not isinstance(obj.get("mediaDefinitions"),list):continue
  configs.append(obj)
 return configs
def main():
 status,_,_,raw=get(HOME)
 catalog=Links();catalog.feed(raw.decode("utf8","replace"))
 found=[]
 for text in catalog.links:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(HOME,html.unescape(text)))
  if u.scheme!="https" or u.hostname not in ("www.pornhub.com","pornhub.com") or u.path!="/view_video.php":
   continue
  vk=urllib.parse.parse_qs(u.query).get("viewkey",[""])[0]
  if re.fullmatch(r"[a-zA-Z0-9_-]{5,50}",vk) and u.geturl() not in found:found.append(u.geturl())
 assert found,"No first-party selected videos"
 selected=found[0]
 status,_,mime,raw=get(selected,HOME)
 assert status==200 and mime=="text/html"
 configs=extract(raw.decode("utf8","replace"))
 print(json.dumps({"stage":"EXACT_SELECTED_FLASHVARS","http":status,
  "first_party_selected":True,"count":len(configs),
  "config_video_ids":[bool(o.get("video_id") or o.get("videoId")) for o in configs],
  "media_definition_counts":[len(o.get("mediaDefinitions",[])) for o in configs]}),flush=True)
 assert len(configs)==1,("Not one selected watch config",len(configs))
 cfg=configs[0]
 sources=[]
 for item in cfg.get("mediaDefinitions",[]):
  if not isinstance(item,dict):continue
  media=item.get("videoUrl")
  if not isinstance(media,str):continue
  u=urllib.parse.urlsplit(media.replace("\\/","/"))
  if (u.scheme=="https" and u.hostname and
    (u.hostname=="phncdn.com" or u.hostname.endswith(".phncdn.com")) and
    u.path.endswith(".m3u8") and not u.username and not u.password and
    item.get("format") in ("hls","hls_master")):
   sources.append((int(item.get("quality") or 0),media))
 print(json.dumps({"stage":"PUBLISHED_SELECTED_HLS",
  "eligible_sources":len(sources),
  "qualities":[q for q,_ in sources][:12],
  "media_hosts":sorted(set(urllib.parse.urlsplit(s).hostname for _,s in sources))[:5]}),flush=True)
 if not sources:return 2
 for q,source in sorted(sources,reverse=True)[:2]:
  try:
   with urllib.request.urlopen(request(source,selected),timeout=23) as response:
    data=response.read(100000)
    print(json.dumps({"stage":"REAL_SELECTED_HLS_BYTES",
      "quality":q,"http":response.status,
      "mime":response.headers.get("Content-Type","").split(";")[0],
      "playlist_bytes":len(data),"extm3u":data.lstrip().startswith(b"#EXTM3U"),
      "manifest_host":urllib.parse.urlsplit(source).hostname}),flush=True)
    if response.status==200 and data.lstrip().startswith(b"#EXTM3U"):return 0
  except urllib.error.HTTPError as e:
   print(json.dumps({"stage":"SELECTED_CDN_DENIED","quality":q,"http":e.code}),flush=True)
 return 3
if __name__=="__main__":
 try:raise SystemExit(main())
 except urllib.error.HTTPError as err:
  print(json.dumps({"stage":"SELECTED_PUBLISHER_DENIED","http":err.code,
    "hostname":urllib.parse.urlsplit(err.url).hostname}),flush=True)
  raise SystemExit(4)
 except Exception as err:
  print(json.dumps({"stage":"SELECTED_MEDIA_UNVERIFIED","error_type":type(err).__name__,
    "reason":str(err)[:180]}),flush=True)
  raise SystemExit(5)
