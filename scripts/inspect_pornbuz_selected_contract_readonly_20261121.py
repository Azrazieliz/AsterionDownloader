#!/usr/bin/env python3
"""Inspect user's exact published PornBuz player only; no media download,
URL/token export, unrelated iframe scraping, or unauthorized interaction.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request
from contextlib import closing

class Tags(html.parser.HTMLParser):
 def __init__(self,base):
  super().__init__();self.base=base;self.stack=[];self.frames=[];self.anchors=[];self.videos=[]
  self.script=False;self.script_text=""
 def scope(self):
  return [{"tag":v[0],"id":v[1][:45],"cls":v[2][:90]} for v in self.stack[-5:]]
 def where(self,target):
  uri=urllib.parse.urlsplit(urllib.parse.urljoin(self.base,target))
  path=uri.path;return {
    "host":uri.hostname,"route":path.split("/")[1] if "/" in path else "",
    "mp4":path.lower().endswith(".mp4"),"hls":path.lower().endswith(".m3u8"),
    "query":bool(uri.query)}
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="script" and not a.get("src"):self.script=True
  if tag=="a" and a.get("href"):
   o={"path":self.where(a["href"]),"attrs":sorted(a.keys()),"scope":self.scope()}
   if "streamtape" in (o["path"]["host"] or "") or "sbtape" in (o["path"]["host"] or ""):
    self.anchors.append(o)
  if tag=="iframe":
   self.frames.append({"path":self.where(a.get("src","")),
     "id":a.get("id"),"cls":a.get("class"),"scope":self.scope()})
  if tag in ("video","source"):
   src=a.get("src") or a.get("data-src") or ""
   self.videos.append({"tag":tag,"src":self.where(src) if src else None,
     "data_duration":a.get("data-duration"),"autoplay":"autoplay" in a,
     "scope":self.scope()})
  if tag not in ("meta","br","img","source","link","hr","input","area"):
   self.stack.append((tag,a.get("id",""),a.get("class","")))
 def handle_data(self,data):
  if self.script and len(self.script_text)<3000 and re.search("streamtape|server.{0,8}2",data,re.I):
   self.script_text+=re.sub(r"https?://[^\\s'\\\"]+","[URL]",data[:1800])
 def handle_endtag(self,tag):
  if tag=="script":self.script=False
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:
    del self.stack[i:];break
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_pornbuz_") as temp:
 root=pathlib.Path(temp)
 for name in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  b=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+name],capture_output=True,timeout=20)
  if b.returncode==0 and b.stdout:(root/name).write_bytes(b.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%pornbuz.com%' ORDER BY updated DESC LIMIT 5").fetchall()
 selected=next((v[0] for v in rows if urllib.parse.urlsplit(v[0]).hostname in ("pornbuz.com","www.pornbuz.com")),None)
 if not selected:raise RuntimeError("Selected PornBuz row unavailable")
 req=urllib.request.Request(selected,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=18) as res:
  raw=res.read(700000).decode("utf-8","replace");doc=Tags(res.url);doc.feed(raw)
  print(json.dumps({"provider":"pornbuz","http":res.status,"html_chars":len(raw),
    "frame_structure":doc.frames[:6],"streamtape_anchors":doc.anchors[:8],
    "video_structure":doc.videos[:12],"server2_text_count":len(re.findall("(?i)server\\s*2",raw)),
    "streamtape_text_count":len(re.findall("(?i)streamtape|sbtape",raw))}),flush=True)
