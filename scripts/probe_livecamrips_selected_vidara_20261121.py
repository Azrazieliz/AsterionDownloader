#!/usr/bin/env python3
"""Read-only exact LiveCamRips selected iframe and public Vidara player HTML.
No URL/title/cookie/media fragment is exported or downloaded.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from contextlib import closing
class Frames(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.frames=[];self.videos=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):
   self.frames.append((a.get("id"),urllib.parse.urljoin(self.base,a["src"])))
  if tag in ("video","source"):
   self.videos.append((tag,urllib.parse.urljoin(self.base,a.get("src","")) if a.get("src") else "",a.get("data-duration")))
  if tag=="script" and a.get("src"):self.scripts.append(urllib.parse.urljoin(self.base,a.get("src","")))
def read(url,ref=None):
 h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity"}
 if ref:h["Referer"]=ref
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=18) as res:
   return res.status,res.url,res.read(700000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:return e.code,e.url,e.read(100000).decode("utf-8","replace")
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_livecam_vidara_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%livecamrips.to%' ORDER BY updated DESC LIMIT 25").fetchall()
 selected=next((x[0] for x in rows if urllib.parse.urlsplit(x[0]).hostname in ("livecamrips.to","www.livecamrips.to")),None)
 if not selected:raise RuntimeError("no selected LiveCamRips")
 code,final,html=read(selected);page=Frames(final);page.feed(html)
 frame=next((u for id,u in page.frames if id=="iframe-video" and
   urllib.parse.urlsplit(u).hostname=="vidara.to" and
   re.fullmatch(r"/[A-Za-z0-9_-]{8,50}/?",urllib.parse.urlsplit(u).path)),None)
 if not frame:raise RuntimeError("no exact selected Vidara frame")
 rc,ru,body=read(frame,selected);player=Frames(ru);player.feed(body)
 def shape(url):
  u=urllib.parse.urlsplit(url)
  return {"host":u.hostname,"ext":u.path.rsplit(".",1)[-1][:8] if "." in u.path else "",
    "route":u.path.split("/")[1] if len(u.path.split("/"))>1 else "",
    "has_query":bool(u.query)}
 media=re.findall(r"""https?://[^'"\s<>]{10,1500}?(?:\.m3u8|\.mp4)(?:\?[^'"\s<>]{0,200})?""",body,re.I)
 print(json.dumps({"publisher":"livecamrips","selected_page_http":code,
   "selected_main_frame":True,"iframe_count":len(page.frames),
   "player_http":rc,"player_final_host":urllib.parse.urlsplit(ru).hostname,
   "player_html_bytes":len(body),
   "has_access_gate":bool(re.search(r"captcha|cf-turnstile|verify you are human",body[:15000],re.I)),
   "video_elements":[{"type":tag,"source":shape(url) if url else None,"duration":duration} for tag,url,duration in player.videos[:6]],
   "nested_frames":[{"id":id,"source":shape(u)} for id,u in player.frames[:6]],
   "published_media_hosts":[shape(x) for x in media[:8]],
   "script_hosts":[shape(u) for u in player.scripts[:12]],
   "markers":{key:len(re.findall(key,body,re.I)) for key in ("jwplayer","videojs","fluidplayer","sources","m3u8","mp4","duration","streamtape")}}),flush=True)
