#!/usr/bin/env python3
"""Read-only selected MP4Hentai publisher-link and external hoster diagnosis.

Only the actual chosen article's anchored Mirror and Upfiles links. Never
scrape related posts, execute JS, solve challenges or record opaque file IDs
or complete original file URLs in the report.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
PAGE="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Anchors(HTMLParser):
 def __init__(self):super().__init__();self.stack=[];self.active=[];self.matches=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  css=d.get("class","").lower().split()
  parent=any(s[2] for s in self.stack)
  relevant=("entry-content" in css or "post-content" in css)
  blocked=any(q in css for q in ("related","recommended","sidebar","ads","advertisement"))
  inside=parent or relevant
  self.stack.append((tag,inside,inside and not blocked))
  if tag=="a" and d.get("href"):
   self.active.append({"href":d["href"],"title":d.get("title",""),"text":"",
    "inside":inside and not blocked,"depth":len(self.stack)})
 def handle_data(self,s):
  if self.active and len(self.active[-1]["text"])<100:
   self.active[-1]["text"]+=s[:100]
 def handle_endtag(self,tag):
  while self.active and len(self.stack)<=self.active[-1]["depth"] and tag=="a":
   self.matches.append(self.active.pop());break
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:
    self.stack=self.stack[:i];break
def get(url,referer):
 headers={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}
 req=urllib.request.Request(url,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=22) as response:
   return response.status,response.url,response.read(1_600_000).decode("utf8","replace")
 except urllib.error.HTTPError as e:
  return e.code,url,e.read(100000).decode("utf8","replace")
 except Exception as e:
  return type(e).__name__,url,""
def shape(url):
 uri=urllib.parse.urlsplit(url)
 return {"host":uri.hostname,"route":re.sub(r"[A-Za-z0-9_-]{14,}","{id}",uri.path)[:90]}
s,u,b=get(PAGE,PAGE)
print("PUBLISHER",s,len(b),flush=True)
p=Anchors();p.feed(b)
links=[]
for a in p.matches:
 if not a["inside"]:continue
 label=(a["text"]+" "+a["title"]).strip().lower()
 if re.search(r"mirror|upfiles|download|original",label,re.I):
  raw=a["href"].replace("[","%5B").replace("]","%5D").replace(" ","%20")
  link=urllib.parse.urljoin(PAGE,html.unescape(raw))
  if urllib.parse.urlsplit(link).scheme!="https":continue
  links.append((label[:65],link))
print("PUBLISHED",len(links),flush=True)
for label,link in links[:12]:
 status,actual,body=get(link,PAGE)
 parsed=Anchors();parsed.feed(body)
 out=[]
 for a in parsed.matches:
  value=(a["text"]+" "+a["title"]).strip().lower()
  resolved=urllib.parse.urljoin(actual,html.unescape(a["href"]))
  loc=urllib.parse.urlsplit(resolved)
  if loc.scheme!="https":continue
  if re.search(r"download|direct|mirror|server|host|file",value):
   out.append({"label":value[:40],**shape(resolved)})
 print(json.dumps({"published_label":label,**shape(link),
  "http":status,"final_host":urllib.parse.urlsplit(actual).hostname,
  "html_bytes":len(body),"link_candidates":out[:14],
  "gate":bool(re.search("captcha|turnstile|verify you are human|just a moment",
    body[:5000],re.I))}),flush=True)
