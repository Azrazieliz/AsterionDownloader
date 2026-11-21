#!/usr/bin/env python3
"""Context snippets from the exact selected JWPlayer config JS, redacted and
bounded; never expose actual source URLs, session tokens, or selected titles.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request
from contextlib import closing
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
class P(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.stack=[];self.frames=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);scope=" ".join(x[1] for x in self.stack[-7:])
  if tag=="iframe" and a.get("src"):self.frames.append((urllib.parse.urljoin(self.base,a["src"]),scope))
  if tag=="script" and a.get("src"):self.scripts.append(urllib.parse.urljoin(self.base,a["src"]))
  if tag not in ("meta","img","link","input","br","hr"):self.stack.append((tag,a.get("class","")))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
def get(url,ref=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if ref:h["Referer"]=ref
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=15) as res:
  return res.url,res.read(650000).decode("utf8","replace")
def clean(src):
 src=re.sub(r"https?://[^'\"<>\\\s,;)}]{6,1000}","[URL]",src)
 src=re.sub(r"(?i)(?:token|cookie|authorization|key)\\s*[:=]\\s*['\"][^'\"]+['\"]","[SENSITIVE]",src)
 src=re.sub(r"['\"][A-Za-z0-9+/=_-]{35,}['\"]","'[OPAQUE]'",src)
 return re.sub(r"\\s+"," ",src)[:270]
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance");a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_buz_js_shape_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%pornbuz.com%' ORDER BY updated DESC LIMIT 8").fetchall()
 selected=next((r[0] for r in rows if urllib.parse.urlsplit(r[0]).hostname in ("pornbuz.com","www.pornbuz.com")),None)
 if not selected:raise RuntimeError("no selected article")
 final,html=get(selected);doc=P(final);doc.feed(html)
 found=list(dict.fromkeys(x for x,scope in doc.frames if urllib.parse.urlsplit(x).hostname=="player.watchstreamus.com" and "responsive-player" in scope))
 if len(found)!=1:raise RuntimeError("selected player ambiguous")
 embed=found[0];final,body=get(embed,selected);doc=P(final);doc.feed(body)
 for num,script in enumerate(doc.scripts):
  if urllib.parse.urlsplit(script).hostname!="player.watchstreamus.com":continue
  _,js=get(script,embed)
  marks=list(re.finditer(r"(?i)jwplayer|playlist|sources|fetch\\s*\\(|duration|file\\s*[:=]|mediaId|manifest|\\bapi\\b",js))
  if not marks:continue
  snippets=[]
  for m in marks[:7]:
   snippets.append(clean(js[max(0,m.start()-85):min(len(js),m.end()+110)]))
  print(json.dumps({"script_index":num,"bytes":len(js),"markers":len(marks),
   "structure":snippets}),flush=True)
