#!/usr/bin/env python3
"""Read-only current player contracts: selected JAVIDEO, nhplayer, MP4Hentai.

Only follows exact selected publisher links. Never enumerates unrelated
players, downloads videos, bypasses authentication or persists protected data.
"""
import json, re, html, urllib.parse, urllib.request, urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
SELECTED={
 "uncensored":"https://nhplayer.com/v/Z2flAXXPucyCbVJ/",
 "javideo":"https://javideo.net/en/fc2ppv-4994603",
 "mp4hentai":"https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/",
}
class Tags(HTMLParser):
 def __init__(self):
  super().__init__();self.scripts=[];self.frames=[];self.buttons=[];self.media=[];self.current=False;self.code="";self.inlines=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="script":
   if a.get("src"):self.scripts.append(a["src"])
   else:self.current=True;self.code=""
  if tag=="iframe":self.frames.append(a)
  if tag in ("video","source"):self.media.append(a)
  if tag in ("button","input"):self.buttons.append(a)
 def handle_data(self,s):
  if self.current and len(self.code)<150000:self.code+=s
 def handle_endtag(self,tag):
  if tag=="script" and self.current:self.inlines.append(self.code);self.current=False
def get(url,ref=""):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml"}
 if ref:h["Referer"]=ref
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=17) as r:
   return r.status,r.url,r.read(2_000_000).decode("utf8","replace")
 except urllib.error.HTTPError as e:return e.code,url,""
 except Exception as e:return type(e).__name__,url,""
def small(u):
 p=urllib.parse.urlsplit(u);path=p.path
 path=re.sub(r"/[A-Za-z0-9_-]{17,}",'/{opaque}',path)
 return (p.hostname or "")+path[:105]
def trace(name,url):
 status,final,markup=get(url)
 p=Tags();p.feed(markup)
 out={"provider":name,"http":status,"chars":len(markup),
      "frames":[{"shape":small(urllib.parse.urljoin(final,a.get("src",""))),
                 "id":a.get("id","")[:45],"class":a.get("class","")[:70]}
          for a in p.frames[:12]],
      "external_scripts":[small(urllib.parse.urljoin(final,s)) for s in p.scripts[:15]],
      "buttons":[{"id":a.get("id","")[:35],"class":a.get("class","")[:60],
                  "data_testid":a.get("data-testid","")[:35]} for a in p.buttons[:20]],
      "media":[{"src_shape":small(urllib.parse.urljoin(final,m.get("src",""))),
                "type":m.get("type","")} for m in p.media[:7]],
      "signals":{key:len(re.findall(pattern,markup,re.I)) for key,pattern in {
        "iframe":r"<iframe","video":r"<video","source":r"<source",
        "streamtape":r"streamtape|sbtape",
        "download":r"download","atob":r"atob\s*\(",
        "packed":r"eval\s*\(\s*function\s*\(p,a,c,k",
        "jwplayer":r"jwplayer|jwplayer\(","videojs":r"videojs|video-js",
        "captcha":r"just a moment|verify you are human|cf-turnstile|challenge",
        "dynamic":r"player|embed|stream|play",}.items()},
      "inline_player_shapes":[]}
 for script in p.inlines:
  if not re.search(r"player|video|embed|stream|iframe|source",script,re.I):continue
  for expr in (r"(?:document\.)?getElementById\(['\"]([^'\"\n]{2,70})",
               r"(?:src|file|source|url)\s*[:=]\s*['\"](https?[^'\"\s]{12,500})"):
   for m in list(re.finditer(expr,script,re.I))[:6]:
    out["inline_player_shapes"].append(
      m.group(1)[:60] if m.group(1).startswith("video") else
      small(m.group(1)) if m.group(1).startswith("http") else m.group(1)[:60])
 # Only explicitly linked, same-provider nhplayer child frame; do not follow adverts.
 if name=="uncensored":
  nested=[]
  for f in p.frames:
   u=urllib.parse.urljoin(final,html.unescape(f.get("src","")))
   h=urllib.parse.urlsplit(u).hostname
   if h not in ("nhplayer.com","www.nhplayer.com"):continue
   code,_,body=get(u,final)
   sub=Tags();sub.feed(body)
   nested.append({"http":code,"route":small(u),"bytes":len(body),
     "sub_frames":len(sub.frames),"video":len(sub.media),
     "external_script_paths":[small(urllib.parse.urljoin(u,x)) for x in sub.scripts[:6]]})
  out["exact_nested"]=nested
 return out
if __name__=="__main__":
 from concurrent.futures import ThreadPoolExecutor
 with ThreadPoolExecutor(max_workers=3) as e:
  for r in e.map(lambda kv:trace(*kv),SELECTED.items()):
   print(json.dumps(r),flush=True)
