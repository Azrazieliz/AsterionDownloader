#!/usr/bin/env python3
"""Exact selected UncensoredHentai official nhplayer public script inspection.

Read-only first-party HTTPS for selected /v/<id> and its own JS scripts.
Only emit safe source-shape counts; do not attempt a bypass, external ad,
captcha solving, unrelated media or guessed hoster originals.
"""
import json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
SELECTED="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
OWNER="https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Scripts(HTMLParser):
 def __init__(self):super().__init__();self.paths=[];self.inlines=[];self.curr=None
 def handle_starttag(self,t,attrs):
  d=dict(attrs)
  if t=="script":
   if d.get("src"):self.paths.append(d["src"])
   else:self.curr=""
 def handle_data(self,s):
  if self.curr is not None and len(self.curr)<200000:self.curr+=s
 def handle_endtag(self,t):
  if t=="script" and self.curr is not None:self.inlines.append(self.curr);self.curr=None
def get(url,ref):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,
  "Accept-Encoding":"identity"})
 try:
  with urllib.request.urlopen(req,timeout=20) as r:
   return r.status,r.read(600000).decode("utf8","replace")
 except urllib.error.HTTPError as e:return e.code,e.read(100000).decode("utf8","replace")
 except Exception as e:return type(e).__name__,""
status,body=get(SELECTED,OWNER)
scan=Scripts();scan.feed(body)
print(json.dumps({"player_http":status,"html_bytes":len(body),
 "first_party_js":[urllib.parse.urlsplit(urllib.parse.urljoin(SELECTED,x)).path
   for x in scan.paths if urllib.parse.urlsplit(urllib.parse.urljoin(SELECTED,x)).hostname=="nhplayer.com"][:20],
 "inline_count":len(scan.inlines),
 "page_m3u8_mentions":len(re.findall("m3u8",body,re.I)),
 "page_mp4_mentions":len(re.findall(".mp4",body,re.I))}),flush=True)
for raw in scan.paths[:25]:
 url=urllib.parse.urljoin(SELECTED,raw)
 u=urllib.parse.urlsplit(url)
 if u.scheme!="https" or u.hostname!="nhplayer.com":continue
 code,script=get(url,SELECTED)
 markers={name:len(re.findall(expr,script,re.I)) for name,expr in {
  "m3u8":r"m3u8","mp4":r"\.mp4","api":r"\bapi\b",
  "xhr":r"fetch\(|XMLHttpRequest|\.ajax","file":r"\bfile\b",
  "jwplayer":r"jwplayer","hls":r"\bhls\b",
  "captcha":r"turnstile|captcha|challenge",
  "atob":r"atob\(","iframe":r"iframe","post_id":r"postId|videoId|post_id"
 }.items()}
 urls=re.findall(r"https?://[^\s\"'<>]{12,450}",script)
 shapes=[]
 for lit in urls[:30]:
  dest=urllib.parse.urlsplit(lit)
  shapes.append({"host":dest.hostname,"path":re.sub(
   r"/[A-Za-z0-9_-]{15,}","/{opaque}",dest.path)[:85]})
 print(json.dumps({"first_party_asset":u.path,"http":code,
  "bytes":len(script),"markers":markers,"published_url_shapes":shapes[:18]}),
   flush=True)
