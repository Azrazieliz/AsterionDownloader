#!/usr/bin/env python3
"""Read-only full-player API probe for existing TaiAV selected movie.

Uses only the movie ID and option values published in its exact article;
GETs first-party /api/getmovie, then up to 8 KB of its declared HLS playlist.
Never requests previewvideo or downloads media fragments. No guessed quality,
account login, media URLs, cookies or tokens appear in output.
"""
import json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Entries(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.quality=[];self.scope=False
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.links.append(a["href"])
  if tag=="select" and ("hdselect" in a.get("class","") or
      "quality" in a.get("id","")):self.scope=True
  if tag=="option" and self.scope and "value" in a:self.quality.append(a["value"])
 def handle_endtag(self,tag):
  if tag=="select":self.scope=False
def fetch(url,referer=None,limit=2000000,headers=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if referer:h["Referer"]=referer
 h.update(headers or {})
 request=urllib.request.Request(url,headers=h)
 with urllib.request.urlopen(request,timeout=19) as response:
  return response.status,response.url,response.read(limit)
def article():
 code,url,body=fetch("https://taiav.com/cn/")
 p=Entries();p.feed(body.decode("utf8","replace"))
 choices=[]
 for raw in p.links:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(url,raw))
  if u.scheme=="https" and u.hostname in ("taiav.com","www.taiav.com") and re.fullmatch(r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?",u.path,re.I):
   choices.append(u.geturl())
 if not choices:raise ValueError("NO_SELECTED_MOVIE")
 code,page,body=fetch(choices[0])
 return page,body.decode("utf8","replace")
def main():
 page,html=article()
 item=urllib.parse.urlsplit(page)
 movieid=item.path.rstrip('/').split('/')[-1]
 assert re.fullmatch(r"[a-f0-9]{24}",movieid)
 p=Entries();p.feed(html)
 assign=list(re.finditer(r"(?i)\b(?:var|let|const)\s+hd\s*=\s*([^;\r\n]{1,150})",html))
 quality=[
  x for x in p.quality if re.fullmatch(r"[A-Za-z0-9_-]{1,20}",x)
 ]
 print(json.dumps({"stage":"TAIAV_SELECTED_QUALITY_DISCOVERY",
  "movie_id_shape":"hex24","html_quality_options":quality[:8],
  "hd_initializers":[m.group(1).strip()[:100] for m in assign[:6]],
  "getmovie_ajax_observed":"/api/getmovie" in html,
  "previewvideo_is_separate":"/previewvideo" in html}),flush=True)
 # Use only quality values advertised in the exact selected page.
 if not quality:
  print(json.dumps({"stage":"TAIAV_PLAYER_API","status":"NO_PUBLISHED_QUALITY"}))
  return 2
 for value in list(dict.fromkeys(quality))[:3]:
  url="https://taiav.com/api/getmovie?"+urllib.parse.urlencode({
   "type":value,"id":movieid})
  try:
   status,actual,body=fetch(url,page,200000,
    {"Accept":"application/json","X-Requested-With":"XMLHttpRequest"})
   if urllib.parse.urlsplit(actual).hostname!="taiav.com":raise ValueError("API_CROSS_SITE")
   data=json.loads(body.decode("utf8","replace"))
   media=data.get("m3u8")
   result={"stage":"TAIAV_PLAYER_API","quality":value,"status":status,
    "json_keys":sorted(data)[:15],"declares_hls":isinstance(media,str) and bool(media),
    "message_present":bool(data.get("message"))}
   if isinstance(media,str) and media:
    absolute=urllib.parse.urljoin(page,media)
    u=urllib.parse.urlsplit(absolute)
    result["source_relative"]=not bool(urllib.parse.urlsplit(media).scheme)
    result["media_scheme"]=u.scheme;result["media_host"]=u.hostname
    result["media_ext"]=u.path.rsplit(".",1)[-1] if "." in u.path else "none"
    if u.scheme=="https" and re.search(r"\.m3u8$",u.path,re.I):
     try:
      playlist_status,_,body=fetch(absolute,page,12000)
      playlist=body.decode("utf8","replace")
      result["playlist_status"]=playlist_status
      result["valid_m3u8"]=playlist.lstrip().startswith("#EXTM3U")
      result["master_playlist"]="#EXT-X-STREAM-INF:" in playlist
      result["vod_endlist"]="#EXT-X-ENDLIST" in playlist
      result["duration_tags"]=len(re.findall(r"(?m)^#EXTINF:",playlist))
     except Exception as exc:
      result["playlist_error"]=exc.__class__.__name__
   print(json.dumps(result),flush=True)
   if result.get("valid_m3u8"):return 0
  except Exception as exc:
   print(json.dumps({"stage":"TAIAV_PLAYER_API","quality":value,
       "error":exc.__class__.__name__}),flush=True)
 return 3
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as e:
  print(json.dumps({"stage":"TAIAV_SELECTED","error":e.__class__.__name__}),
      flush=True)
  raise SystemExit(5)
