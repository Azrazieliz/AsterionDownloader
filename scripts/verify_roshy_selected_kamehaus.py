#!/usr/bin/env python3
"""Real Roshy selected Beeteam368 original-stream metadata test.

Inspects only user-selected official post and official embedded player. Uses
the EXACT yt_dlp wheel bundled in the APK. Does not save video or authenticate
to third-party file hosts. Prints only media hosts, not expiring URLs.
"""
import html,json,re,subprocess,sys,tempfile,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path
PAGE="https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Frames(HTMLParser):
 def __init__(self):super().__init__();self.srcs=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="iframe" and d.get("src"):self.srcs.append(d["src"])
def page(url,referer=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity"}
 if referer:h["Referer"]=referer
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=22) as resp:
  assert resp.status==200
  return resp.read(1700000).decode("utf8","replace")
def main():
 text=page(PAGE)
 controls=re.findall(r'id=["\'](beeteam368_player_[0-9]+)["\']',text)
 controls=list(dict.fromkeys(controls))
 assert len(controls)==1,("not one selected main player",controls)
 ident=controls[0]
 expr=r"""jQuery\(\s*['"]#"""+re.escape(ident)+r"""['"]\s*\)\s*\.beeteam368_pro_player\(\s*"""
 m=re.search(expr,text)
 assert m,("official selected player initializer missing")
 config,end=json.JSONDecoder().raw_decode(text[m.end():].lstrip())
 assert isinstance(config,dict)
 raw=config.get("video_url","")
 mode=config.get("video_mode")
 parser=Frames();parser.feed(raw)
 embeds=[]
 for candidate in parser.srcs:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,html.unescape(candidate)))
  if (u.scheme=="https" and u.hostname=="kamehaus.net" and
      re.fullmatch(r"/e/[a-zA-Z0-9_-]{6,80}/?",u.path)):
   embeds.append(u.geturl())
 print(json.dumps({"stage":"first_party_selected_original_stream",
   "selected_player":ident,"video_mode":mode,
   "published_kamehaus_embeds":len(embeds)}),flush=True)
 if mode!="embed" or len(embeds)!=1:return 2
 embed=embeds[0]
 rendered=page(embed,PAGE)
 scan=Frames();scan.feed(rendered)
 print(json.dumps({"stage":"official_embed_access","html_bytes":len(rendered),
   "mp4_text":len(re.findall(r"\\.mp4",rendered,re.I)),
   "hls_text":len(re.findall(r"\\.m3u8",rendered,re.I)),
   "iframe_count":len(scan.srcs),"host":"kamehaus.net"}),flush=True)
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 assert yt_dlp.version.__version__=="2026.10.26"
 opts={"quiet":True,"no_warnings":True,"skip_download":True,
   "noplaylist":True,"socket_timeout":18,"retries":0,
   "http_headers":{"User-Agent":UA,"Referer":PAGE}}
 try:
  with yt_dlp.YoutubeDL(opts) as ydl:
   info=ydl.extract_info(embed,download=False)
 except Exception as e:
  print(json.dumps({"provider":"roshy","status":"EXTRACTOR_UNRESOLVED",
   "type":type(e).__name__,"message":str(e)[:160]}),flush=True)
  return 3
 formats=[f for f in info.get("formats",[]) if f.get("url") and
   f.get("vcodec")!="none"]
 print(json.dumps({"provider":"roshy","status":
   "EXTRACTOR_METADATA_VERIFIED_NOT_COMPLETE" if formats else "NO_VIDEO_FORMATS",
   "formats":len(formats),"duration":info.get("duration"),
   "media_hosts":sorted(set(urllib.parse.urlsplit(f["url"]).hostname for f in formats))[:5]}),flush=True)
 return 0 if formats else 4
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"roshy","status":"FAILED_NOT_COMPLETE",
    "type":type(e).__name__,"error":str(e)[:210]}),flush=True);sys.exit(5)
