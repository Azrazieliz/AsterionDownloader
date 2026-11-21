#!/usr/bin/env python3
"""Public selected PornBuz active main-player embed structure, no media
downloads, no signed URLs or tokens in output; not a gateway bypass.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from contextlib import closing
class Doc(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.stack=[];self.frames=[];self.video=[];self.sources=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);scope=" ".join(x[2] for x in self.stack[-7:])
  if tag=="iframe" and a.get("src"):self.frames.append((urllib.parse.urljoin(self.base,a["src"]),scope))
  if tag in ("video","source"):
   self.video.append((tag,a.get("src"),a.get("data-src"),a.get("duration"),scope[:150]))
  if tag=="script" and a.get("src"):
   self.scripts.append(urllib.parse.urljoin(self.base,a["src"]))
  if tag not in ("meta","img","source","link","input","br","hr"):self.stack.append((tag,a.get("id",""),a.get("class","")))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
def fetch(url,referer=None):
 h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity"}
 if referer:h["Referer"]=referer
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=18) as res:
   return res.status,res.url,res.read(750000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:return e.code,e.url,e.read(65000).decode("utf-8","replace")
def uri_shape(url):
 p=urllib.parse.urlsplit(url)
 return {"host":p.hostname,"ext":p.path.rsplit(".",1)[-1][:8] if "." in p.path else "",
     "path_segments":len(p.path.split("/")),"query":bool(p.query)}
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_pornbuz_main_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%pornbuz.com%' ORDER BY updated DESC LIMIT 7").fetchall()
 selected=next((u[0] for u in rows if urllib.parse.urlsplit(u[0]).hostname in ("pornbuz.com","www.pornbuz.com")),None)
 if not selected:raise RuntimeError("no selected post")
 code,final,html=fetch(selected);doc=Doc(final);doc.feed(html)
 frames=[url for url,scope in doc.frames if
    urllib.parse.urlsplit(url).hostname=="player.watchstreamus.com"
    and "responsive-player" in scope and "video-player" in scope]
 if len(set(frames))!=1:
  print(json.dumps({"stage":"ACTIVE_SOURCE_AMBIGUOUS","matches":len(set(frames))}));raise SystemExit(2)
 player=frames[0]
 if not re.fullmatch(r"/embed/[A-Za-z0-9_-]{5,150}/?",urllib.parse.urlsplit(player).path):
  raise RuntimeError("unexpected selected player route")
 ec,ef,body=fetch(player,selected);inner=Doc(ef);inner.feed(body)
 hrefs=re.findall(r"""(?i)(?:https?:)?//[^'"<>\\\s]{10,2100}?(?:\\.mp4|\\.m3u8)(?:\\?[^'"<>\\\s]{0,200})?""",body)
 print(json.dumps({"stage":"EXACT_SELECTED_MAIN_PLAYER","article_http":code,
  "article_frames":len(doc.frames),"selected_main_player_count":len(set(frames)),
  "player_host":urllib.parse.urlsplit(player).hostname,"player_http":ec,
  "player_final_host":urllib.parse.urlsplit(ef).hostname,"html_length":len(body),
  "nested_iframe_hosts":[uri_shape(u) for u,_ in inner.frames[:8]],
  "video_elements":inner.video[:8],"script_hosts":[uri_shape(u) for u in inner.scripts[:12]],
  "named_media_hosts":[uri_shape(u) for u in hrefs[:12]],
  "native_play_markers":{k:len(re.findall(re.escape(k),body,re.I)) for k in
    ("video-js","jwplayer","fluidplayer","player.src","hls.js","sources","file:","html5","video_url","duration")},
  "gate":bool(re.search(r"captcha|cf-turnstile|verify you are human|just a moment",body[:12000],re.I))
 }),flush=True)
