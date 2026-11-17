#!/usr/bin/env python3
"""Read-only second stage of precisely published MP4Hentai download hosters.

The selected article's Mirror anchor resolves to mirrored.to /files/<ID>
after its own 'Click to view download links' route; Upfiles redirects to
upfilesgo.com. Inspect public second pages and label/form structure ONLY;
never bypass captcha, login, ads or fabricate file URLs. Output URL hosts,
not opaque file tokens, nor any arbitrary third-party video.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
PUBLISHER="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"
TARGETS={
 "selected_mirror_links":"https://www.mirrored.to/files/DF7298C6/",
 "selected_upfiles":"https://upfiles.com/nGfW"
}
class P(HTMLParser):
 def __init__(self):super().__init__();self.stack=[];self.links=[];self.forms=[];self.active=None
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):
   self.active={"attrs":d,"text":""}
   self.links.append(self.active)
  if tag=="form":
   self.forms.append({"action":d.get("action",""),"method":d.get("method",""),
    "id":d.get("id",""),"class":d.get("class",""),"fields":[]})
  elif tag=="input" and self.forms:
   self.forms[-1]["fields"].append({
    "name":d.get("name","")[:45],"type":d.get("type","")[:20]})
 def handle_data(self,data):
  if self.active and len(self.active["text"])<80:self.active["text"]+=data[:80]
 def handle_endtag(self,tag):
  if tag=="a":self.active=None
def get(u,ref):
 headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity",
  "Accept":"text/html,application/xhtml+xml"}
 try:
  with urllib.request.urlopen(urllib.request.Request(u,headers=headers),
      timeout=20) as response:
   return response.status,response.url,response.read(1200000).decode("utf8","replace")
 except urllib.error.HTTPError as exc:
  return exc.code,u,exc.read(250000).decode("utf8","replace")
 except Exception as exc:
  return type(exc).__name__,u,""
for name,address in TARGETS.items():
 code,final,page=get(address,PUBLISHER)
 parser=P();parser.feed(page)
 links=[]
 for anchor in parser.links:
  dest=urllib.parse.urljoin(final,html.unescape(anchor["attrs"].get("href","")))
  uri=urllib.parse.urlsplit(dest)
  if uri.scheme!="https" or not uri.hostname:continue
  t=(anchor["text"]+" "+anchor["attrs"].get("title","")).strip()
  if (re.search(r"download|file|links?|server|mirror|host|upfiles|go",t,re.I)
    or uri.hostname not in ("www.mirrored.to","mirrored.to","upfilesgo.com","upfiles.com")):
   links.append({"label":t[:45],"host":uri.hostname,
     "path":re.sub(r"/[A-Za-z0-9_-]{11,}","/{opaque}",uri.path[:85]),
     "rel":anchor["attrs"].get("rel","")[:40],
     "download":anchor["attrs"].get("download") is not None})
 forms=[]
 for form in parser.forms[:15]:
  action=urllib.parse.urljoin(final,form["action"])
  u=urllib.parse.urlsplit(action)
  forms.append({"host":u.hostname,"path":u.path[:80],
   "method":form["method"],"id":form["id"],"fields":form["fields"][:12]})
 print(json.dumps({"stage":name,"http":code,"final_host":
  urllib.parse.urlsplit(final).hostname,"bytes":len(page),
  "anchors":len(parser.links),"likely_download_links":links[:25],
  "forms":forms,
  "captcha_gate":bool(re.search(r"turnstile|captcha|verify you are human|just a moment",
    page[:20000],re.I)),
  "direct_mp4_literals":len(re.findall(r"https?://[^\s'\"<>]+\.mp4",page,re.I))}),flush=True)
