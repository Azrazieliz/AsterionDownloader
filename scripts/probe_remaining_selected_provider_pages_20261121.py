#!/usr/bin/env python3
"""Exact owner-selected published watch page status and player contracts.
Reads only public HTML. Does not export URLs, titles, login data, media bytes.
"""
import argparse,collections,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from contextlib import closing
class Tags(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.stack=[];self.videos=[];self.iframes=[];self.player_ids=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe" and a.get("src"):
   u=urllib.parse.urlsplit(urllib.parse.urljoin(self.base,a["src"]))
   self.iframes.append({"host":u.hostname,"path_type":u.path.split("/")[1] if len(u.path.split("/"))>1 else "",
     "has_query":bool(u.query),"scope":"/".join(v[1] for v in self.stack[-2:])[:100]})
  if tag in ("video","source"):
   u=urllib.parse.urlsplit(urllib.parse.urljoin(self.base,a.get("src","")))
   self.videos.append({"host":u.hostname if a.get("src") else "",
     "source_ext":u.path.rsplit(".",1)[-1][:8] if "." in u.path else "",
     "scope":"/".join(v[1] for v in self.stack[-2:])[:100]})
  if a.get("id") and re.search("player|video|embed|kt_",a["id"],re.I):
   self.player_ids.append(a["id"][:35])
  if tag not in ("meta","img","source","link","input","br","hr"):self.stack.append((tag,a.get("class","")))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break

p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_provider_pages_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos ORDER BY updated DESC LIMIT 1400").fetchall()
 urls=[item[0] for item in rows]
 hosts=["pimpbunny.com","avple.tv","livecamrips.to","uncensoredhentai.xxx","javideo.net"]
 for host in hosts:
  found=next((u for u in urls if (urllib.parse.urlsplit(u).hostname or "").lower()
     .removeprefix("www.").removeprefix("en.")==host),None)
  if not found:print(json.dumps({"host":host,"status":"NO_SELECTED_ROW"}),flush=True);continue
  headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
    "Accept":"text/html,application/xhtml+xml","Accept-Encoding":"identity","Accept-Language":"en-US,en;q=0.8"}
  try:
   with urllib.request.urlopen(urllib.request.Request(found,headers=headers),timeout=20) as res:
    code=res.status;url=res.url;page=res.read(700000).decode("utf-8","replace")
  except urllib.error.HTTPError as e:
   code=e.code;url=e.url;page=e.read(90000).decode("utf-8","replace")
  except Exception as e:
   print(json.dumps({"host":host,"status":type(e).__name__}),flush=True);continue
  tags=Tags(url);tags.feed(page)
  counts={label:len(re.findall(pattern,page,re.I)) for label,pattern in (
    ("flashvars",r"\bflashvars\b"),("video_url",r"\bvideo_url\b"),
    ("get_file",r"/get_file/"),("selected_hls_data",r"data-src\s*=\s*['\"][^'\"]+\.m3u8"),
    ("nhplayer",r"nhplayer\.com"),("play_widget",r"clickfakeplayer"),
    ("embed_frame",r"<iframe"),("jwplayer",r"\bjwplayer\b"))}
  age=bool(re.search(r"age verification|parental control|access not available|verify you are human|cf-turnstile|captcha",page[:28000],re.I))
  print(json.dumps({"host":host,"http":code,"html_bytes":len(page),
    "final_host":urllib.parse.urlsplit(url).hostname,"challenge_or_access_gate":age,
    "player_ids":tags.player_ids[:8],"video_elements":tags.videos[:4],
    "iframe_elements":tags.iframes[:5],"key_counts":counts}),flush=True)
