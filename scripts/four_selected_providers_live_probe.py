#!/usr/bin/env python3
"""Current first-party selected-video routes for Jav Guru/JAVIDEO/Pornhub/HelloPorn.

Bounded first-party GET, no cookies, no login/challenge bypass, no unrelated
media or autoplay advertisement extraction, no signed URLs in output.
Only inspect selected publisher pages and one canonical publisher-linked embed.
"""
import concurrent.futures,html,json,re,urllib.error,urllib.parse,urllib.request
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
TARGETS={
 "jav_guru":"https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/",
 "javideo":"https://javideo.net/en/fc2ppv-4994603",
 "helloporn_catalog":"https://helloporn.com/",
 "pornhub_catalog":"https://www.pornhub.com/",
 "helloporn_previous":"https://helloporn.com/video-115854-something",
}
class Tags(HTMLParser):
 def __init__(self):
  super().__init__();self.anchors=[];self.frames=[];self.media=[];self.js=[];self.current=None;self.text=""
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):self.anchors.append((a["href"],a.get("class",""),a.get("title","")))
  if tag=="iframe":self.frames.append(a)
  if tag in ("video","source"):self.media.append(a)
  if tag=="script":
   if a.get("src"):self.js.append(("external",a["src"]))
   else:self.current=""
 def handle_data(self,data):
  if self.current is not None and len(self.current)<450000:self.current+=data
 def handle_endtag(self,tag):
  if tag=="script" and self.current is not None:
   self.js.append(("inline",self.current));self.current=None
def get(url,ref=None,limit=1500000):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml,*/*",
   "Accept-Language":"en-US,en;q=0.9"}
 if ref:h["Referer"]=ref
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=22) as r:
   return {"http":r.status,"url":r.url,"content_type":r.headers.get("Content-Type","").split(";")[0],
           "redirected":r.url!=url},r.read(limit).decode("utf8","replace")
 except urllib.error.HTTPError as e:
  return {"http":e.code,"url":e.url,"content_type":e.headers.get("Content-Type","").split(";")[0]},e.read(120000).decode("utf8","replace")
 except Exception as e:return {"http":0,"error":type(e).__name__+":"+str(e)[:85],"url":url},""
def summarize(name,url):
 info,body=get(url)
 p=Tags();p.feed(body)
 host=urllib.parse.urlsplit(info["url"]).hostname or ""
 def sources(hostname,pattern):
  out=[]
  for href,*_ in p.anchors:
   u=urllib.parse.urlsplit(urllib.parse.urljoin(info["url"],html.unescape(href)))
   if u.scheme=="https" and u.hostname in hostname and re.fullmatch(pattern,u.path,re.I):
    if u.geturl() not in out:out.append(u.geturl())
  return out
 links=(
  sources({"helloporn.com","www.helloporn.com"},r"/video-[0-9]{1,10}-[a-z0-9-]{3,250}/?")
  if name.startswith("helloporn") else
  sources({"www.pornhub.com","pornhub.com"},r"/view_video\.php")
  if name.startswith("pornhub") else []
 )
 scoped=[]
 if name=="jav_guru":
  for tag,script in p.js:
   if tag=="inline":
    scoped.extend(re.findall(r"""var\s+([a-z]{10})\s*=\s*\{""",script)[:12])
 elif name=="javideo":
  scoped=[{"id":x.group(1),"class":x.group(2)} for x in re.finditer(
   r"""id=['"]([^'"]*(?:player|play)[^'"]*)['"][^>]{0,250}class=['"]([^'"]*)""",body,re.I)][:7]
 result={"provider":name,"status":info.get("http"),"mime":info.get("content_type"),
  "redirected_host":host,"html_bytes":len(body),"article_candidates":len(links),
  "selected_route_samples":[urllib.parse.urlsplit(v).path for v in links[:4]],
  "iframe_hosts":[urllib.parse.urlsplit(urllib.parse.urljoin(info["url"],x.get("src",""))).hostname for x in p.frames[:10]],
  "video_elements":len(p.media),"scripts":len(p.js),"shortcodes_or_controls":scoped[:10],
  "gate":bool(re.search(r"just a moment|verify you are human|cf-turnstile|captcha",body[:35000],re.I))}
 return result,links
def run():
 with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
  out=dict(zip(TARGETS,pool.map(lambda kv:summarize(*kv),TARGETS.items())))
 for name,(result,_) in out.items():print(json.dumps(result),flush=True)
 for name,lookup in (("helloporn_selected","helloporn_catalog"),
                     ("pornhub_selected","pornhub_catalog")):
  links=out[lookup][1]
  if not links:
   print(json.dumps({"provider":name,"status":"NO_PUBLICLY_LISTED_SELECTED_ARTICLE"}),flush=True)
   continue
  selected=links[0]
  p,_=summarize(name,selected)
  p["selected_route"]=urllib.parse.urlsplit(selected).path
  print(json.dumps(p),flush=True)
if __name__=="__main__":run()
