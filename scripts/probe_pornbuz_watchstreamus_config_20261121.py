#!/usr/bin/env python3
"""Read the exact publisher-selected PornBuz main JWPlayer config scripts.
No media requests and no output of signed URLs, credentials or selected titles.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request
from contextlib import closing
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
class P(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.stack=[];self.frames=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);scope=" ".join(v[1] for v in self.stack[-7:])
  if tag=="iframe" and a.get("src"):self.frames.append((urllib.parse.urljoin(self.base,a["src"]),scope))
  if tag=="script" and a.get("src"):self.scripts.append(urllib.parse.urljoin(self.base,a["src"]))
  if tag not in ("meta","img","link","input","br","hr"):self.stack.append((tag,a.get("class","")))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
def get(url,ref=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if ref:h["Referer"]=ref
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=19) as res:
  return res.status,res.url,res.read(950000).decode("utf-8","replace")
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_buz_js_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%pornbuz.com%' ORDER BY updated DESC LIMIT 8").fetchall()
 selected=next((x[0] for x in rows if urllib.parse.urlsplit(x[0]).hostname in ("pornbuz.com","www.pornbuz.com")),None)
 if not selected:raise RuntimeError("no owner selected PornBuz")
 _,page,html=get(selected);parser=P(page);parser.feed(html)
 frames=list(dict.fromkeys(x for x,scope in parser.frames if
   urllib.parse.urlsplit(x).hostname=="player.watchstreamus.com" and
   "responsive-player" in scope))
 if len(frames)!=1:raise RuntimeError("selected main embed not unique")
 embed=frames[0]
 _,final,body=get(embed,selected);player=P(final);player.feed(body)
 out=[]
 for src in player.scripts:
  uri=urllib.parse.urlsplit(src)
  if (uri.scheme!="https" or uri.hostname!="player.watchstreamus.com" or
       not uri.path.endswith(".js")):continue
  try:
   status,finalJs,js=get(src,embed)
   matches=re.findall(r"""(?i)https?://[^'"\s<>\\]{10,2100}(?:\.m3u8|\.mp4)(?:\?[^'"\s<>\\]{0,250})?""",js)
   hosts=sorted(set(urllib.parse.urlsplit(x).hostname for x in matches))
   out.append({"status":status,"path_depth":len(uri.path.split("/")),
       "js_bytes":len(js),"media_hosts":hosts,"media_literals":len(matches),
       "jw_setup":len(re.findall(r"jwplayer\([^)]*\)\.setup",js,re.I)),
       "playlist":len(re.findall(r"playlist|sources|file\s*:",js,re.I)),
       "api_calls":len(re.findall(r"fetch\s*\(|XMLHttpRequest|axios",js,re.I)),
       "duration_literals":len(re.findall(r"duration\s*[:=]",js,re.I)),
       "type_strings":[kind for kind in ("mp4","m3u8","dash","hls","jwplayer") if kind in js.lower()]})
  except Exception as e:out.append({"error":type(e).__name__})
 print(json.dumps({"publisher":"pornbuz","selected_embed":True,
   "same_host_config_scripts":len(out),"configs":out[:8]}),flush=True)
