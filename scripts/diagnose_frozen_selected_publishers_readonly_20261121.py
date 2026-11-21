#!/usr/bin/env python3
"""Read-only first-party publisher checks for exact already-selected phone jobs.
No user URLs, tokens, video titles, media bytes or cookies leave this process.
Never submit forms, bypass gates, or download media.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from contextlib import closing
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36"
class Tags(html.parser.HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.player=0;self.frames=0
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):self.links.append((d.get("href",""),d.get("title","")))
  if tag=="video":self.player+=1
  if tag=="iframe":self.frames+=1
def get(url,referer=None):
 h={"User-Agent":UA,"Accept-Language":"en-US,en;q=0.8","Accept-Encoding":"identity",
    "Accept":"text/html,application/xhtml+xml,application/json;q=0.8"}
 if referer:h["Referer"]=referer
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=16) as res:
   return res.status,res.url,res.read(650000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:
  return e.code,e.url,e.read(120000).decode("utf-8","replace")
 except Exception as e:return type(e).__name__,url,""
p=argparse.ArgumentParser();p.add_argument("--device",required=True)
p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_publishers_") as tmp:
 root=pathlib.Path(tmp)
 for name in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  r=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,
     "cat","databases/"+name],capture_output=True,timeout=20)
  if r.returncode==0 and r.stdout:(root/name).write_bytes(r.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  urls=[row[0] for row in db.execute("SELECT url FROM videos WHERE request_origin='user' ORDER BY updated DESC LIMIT 350").fetchall()]
 for name,host in (("JAV_GURU","jav.guru"),("EPORNER","eporner.com"),("PORNBuz","pornbuz.com"),("JAVIDEO","javideo.net")):
  chosen=next((u for u in urls if (urllib.parse.urlsplit(u).hostname or "").lower().removeprefix("www.")==host),None)
  if not chosen:print(json.dumps({"publisher":name,"status":"NO_SELECTED_ROW"}));continue
  code,final,page=get(chosen)
  doc=Tags();doc.feed(page)
  markers={"age_gate":bool(re.search(r"age verification|parental control|over 18|restricted access|access not available",page[:25000],re.I)),
   "challenge":bool(re.search(r"cf-turnstile|captcha|verify you are human|just a moment",page[:25000],re.I))}
  out={"publisher":name,"http":code,"bytes":len(page),"redirect_host":urllib.parse.urlsplit(final).hostname,
   "video_tags":doc.player,"iframes":doc.frames,"markers":markers}
  if name=="JAV_GURU":
   out["stream_buttons"]=page.count("wp-btn-iframe__shortcode")
   out["publisher_shortcode_configs"]=len(re.findall(r"(?s)\\bvar\\s+[a-z]{10}\\s*=\\s*\\{.{30,1600}?iframe_url",page))
  if name=="EPORNER":
   idmatch=re.search(r"/(?:video-|hd-porn/)([A-Za-z0-9]{6,20})(?:/|$)",urllib.parse.urlsplit(chosen).path)
   if idmatch:
    embed="https://www.eporner.com/embed/"+idmatch.group(1)+"/"
    ec,ef,ep=get(embed,chosen)
    out["official_embed_http"]=ec
    out["official_embed_html_bytes"]=len(ep)
    out["embed_player_markers"]={
      "has_video_tags":"<video" in ep.lower(),
      "has_player_hash":bool(re.search(r"(?i)\\bhash\\s*[:=]\\s*['\\\"][0-9a-f]{32}",ep)),
      "gate":bool(re.search(r"age verification|parental control|over 18|restricted access|access not available",ep[:20000],re.I))}
  if name=="PORNBuz":
   out["server_2_labels"]=len(re.findall(r"(?i)server\\s*2",page))
   out["streamtape_names"]=len(re.findall(r"(?i)streamtape[.]|sbtape[.]",page))
  if name=="JAVIDEO":
   out["play_controls"]={"fakeplayer":"clickfakeplayer" in page,
       "first_party_embed":"play.javideo.net" in page}
  print(json.dumps(out),flush=True)
