#!/usr/bin/env python3
"""Verify one exact public HelloPorn selected article -> VOE player -> media bytes.

Uses only the single VOE iframe in the selected publisher's actual page and
the app-bundled yt-dlp. Neither an iframe nor metadata alone is a success.
No bypass, password, ad media, or arbitrary search. Never print signed URLs.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path

ARTICLE=("https://helloporn.com/video-74763-nurumassage-170407privatepracticemassage-"
         "s01-ericmasterson-summerday-j")
UA=("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 "
    "Chrome/131.0 Mobile Safari/537.36")
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.frames=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
def get(url,ref=None,byterange=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if ref:h["Referer"]=ref
 if byterange:h["Range"]=byterange
 return urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=23)
def main():
 with get(ARTICLE) as r:
  assert r.status==200
  body=r.read(600000).decode("utf-8","replace")
 page=Frames();page.feed(body)
 frames=[]
 for src in page.frames:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(ARTICLE,src))
  if (u.scheme=="https" and u.hostname=="voe.sx" and not u.query and
      re.fullmatch(r"/e/[a-zA-Z0-9_-]{6,80}/?",u.path)):
   frames.append(u.geturl())
 frames=list(dict.fromkeys(frames))
 assert len(frames)==1,("selected VOE player not unambiguous",len(frames))
 frame=frames[0]
 print(json.dumps({"provider":"HelloPorn","stage":"EXACT_SELECTED_VOE",
  "selected_article_http":200,"iframe_count":len(frames)}),flush=True)
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 assert wheel.is_file(),"app-bundled downloader wheel missing"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 assert yt_dlp.version.__version__=="2026.10.26"
 headers={"User-Agent":UA,"Referer":ARTICLE}
 with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,"skip_download":True,
   "noplaylist":True,"socket_timeout":20,"retries":0,
   "http_headers":headers}) as client:
  info=client.extract_info(frame,download=False)
 assert isinstance(info,dict) and info.get("_type")!="playlist"
 formats=[f for f in info.get("formats",[]) if isinstance(f,dict) and
   f.get("vcodec") not in (None,"none") and
   isinstance(f.get("url"),str)]
 print(json.dumps({"provider":"HelloPorn","stage":"REAL_SELECTED_VOE_EXTRACTOR",
  "actual_formats":len(formats),"duration_seconds":info.get("duration"),
  "heights":sorted(set(f.get("height") for f in formats if
   isinstance(f.get("height"),int)))}),flush=True)
 if not formats:return 2
 # Prefer a single directly readable, publisher-selected file, not a trailer
 # from the HelloPorn catalog or a different media on the publisher page.
 chosen=sorted(formats,key=lambda f:(f.get("height") or 0),reverse=True)[0]
 media=chosen["url"]
 u=urllib.parse.urlsplit(media)
 if u.scheme!="https" or not u.hostname or u.username or u.password:
  return 3
 with get(media,frame,"bytes=0-4095") as r:
  sample=r.read(4096)
  mime=r.headers.get("Content-Type","").split(";")[0].lower()
  valid=(r.status==206 and sample[4:8]==b"ftyp") or (
    r.status==200 and sample.lstrip().startswith(b"#EXTM3U"))
  print(json.dumps({"provider":"HelloPorn","stage":"SELECTED_MEDIA_FIRST_BYTES",
   "source_host":u.hostname,"http":r.status,"mime":mime,
   "read_bytes":len(sample),"valid_mp4_or_hls":valid}),flush=True)
  if not valid:return 4
 print(json.dumps({"provider":"HelloPorn",
  "status":"SELECTED_MEDIA_BYTES_VERIFIED_NOT_COMPLETE"}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except urllib.error.HTTPError as ex:
  print(json.dumps({"provider":"HelloPorn","status":"ACCESS_DENIED",
   "http":ex.code,"host":urllib.parse.urlsplit(ex.url).hostname}),flush=True)
  sys.exit(5)
 except Exception as ex:
  print(json.dumps({"provider":"HelloPorn","status":"NOT_VERIFIED",
   "error_type":type(ex).__name__,"reason":str(ex)[:220]}),flush=True)
  sys.exit(6)
