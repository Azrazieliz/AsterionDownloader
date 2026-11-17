#!/usr/bin/env python3
"""Bounded selected-page structure diagnosis. No guessed source URLs,
credentials, download, CAPTCHA interaction or private tokens in output.
"""
import json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
PAGES={
 "nhplayer":"https://nhplayer.com/v/Z2flAXXPucyCbVJ/",
 "hentaihaven":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
 "jav_guru":"https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/",
}
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Structure(HTMLParser):
 def __init__(self):
  super().__init__();self.frames=[];self.videos=[];self.scripts=[]
  self.forms=[];self.buttons=[];self.article_ids=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="iframe":self.frames.append(a)
  if tag in ("video","source"):self.videos.append(a)
  if tag=="script" and a.get("src"):self.scripts.append(a.get("src"))
  if tag=="form":self.forms.append({"id":a.get("id",""),"action":a.get("action","")})
  if tag=="a" and "wp-btn-iframe__shortcode" in a.get("class",""):
   self.buttons.append({"class":a.get("class",""),"keylen":len(a.get("data-localize",""))})
  if tag=="article":self.article_ids.append(a.get("id",""))
def shaped(base,raw):
 u=urllib.parse.urlsplit(urllib.parse.urljoin(base,raw))
 return {"host":u.hostname,"path_shape":"/"+"/".join(
  x if x in ("e","v","embed","player","video","watch","searcho","api","get","file")
  else "{id}" for x in u.path.split("/") if x)[:100],
  "query":bool(u.query)}
def get(url):
 req=urllib.request.Request(url,headers={"User-Agent":UA,
  "Accept-Encoding":"identity","Accept":"text/html,*/*"})
 try:
  with urllib.request.urlopen(req,timeout=22) as res:
   return res.status,res.url,res.read(550000).decode("utf8","replace")
 except urllib.error.HTTPError as error:
  return error.code,error.url,error.read(80000).decode("utf8","replace")
for name,url in PAGES.items():
 try:
  status,final,html=get(url);p=Structure();p.feed(html)
  gates=bool(re.search(r"(?i)just a moment|verify you are human|cf-turnstile|captcha|attention required|cloudflare security",html[:12000]))
  print(json.dumps({"selected":name,"http":status,"bytes":len(html),
   "final_host":urllib.parse.urlsplit(final).hostname,
   "page_is_challenge":gates,"frames":[shaped(final,x.get("src",""))
    for x in p.frames if x.get("src")][:10],
   "videos":[{"has_src":bool(x.get("src")),"has_data_src":bool(x.get("data-src"))}
    for x in p.videos[:8]],
   "article_ids":["post-numeric" if re.match(r"post-\\d+$",x) else
     "other" for x in p.article_ids[:4]],
   "stream_control_count":len(p.buttons),
   "stream_key_lengths":[x["keylen"] for x in p.buttons[:8]],
   "script_hosts":sorted({shaped(final,x)["host"] for x in p.scripts}),
   "forms":[{"id":x["id"],"action_host":shaped(final,x["action"])["host"]}
    for x in p.forms[:6]]}),flush=True)
 except Exception as e: print(json.dumps({"selected":name,
  "error":type(e).__name__,"reason":str(e)[:110]}),flush=True)
