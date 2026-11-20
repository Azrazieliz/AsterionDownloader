#!/usr/bin/env python3
"""Read-only public listing href routes. No images or videos downloaded."""
import urllib.request,urllib.parse,collections,json,html.parser,re
class Audit(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.paths=[];self.links=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag!='a' or not d.get('href'):return
  u=urllib.parse.urlsplit(urllib.parse.urljoin(self.base,d['href']))
  if u.scheme!='https' or not u.hostname:return
  host=u.hostname.removeprefix('www.')
  if host!=urllib.parse.urlsplit(self.base).hostname.removeprefix('www.'):return
  p=u.path or '/'
  if len(p)>250:return
  self.paths.append(p)
  if not any(p==a for a in self.links):self.links.append(p)
def run(name,url):
 try:
  req=urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36','Accept-Encoding':'identity'})
  with urllib.request.urlopen(req,timeout=18) as r:b=r.read(900000).decode('utf8','replace');base=r.url;status=r.status
  d=Audit(base);d.feed(b)
  top=collections.Counter('/'+next((x for x in p.strip('/').split('/') if x),'') for p in d.paths)
  shapes=collections.Counter('/'+'/'.join(('{id}' if x.isdigit() else '{slug}' if len(x)>25 else x) for x in p.strip('/').split('/')[:2]) for p in d.paths)
  print(json.dumps({'name':name,'status':status,'html_size':len(b),'links':len(d.paths),'unique_paths':len(d.links),'prefixes':top.most_common(14),'shapes':shapes.most_common(14),'sample_paths':d.links[:18]}),flush=True)
 except Exception as e:print(json.dumps({'name':name,'error':type(e).__name__,'details':str(e)[:100]}),flush=True)
for name,url in [('4khd-home','https://www.4khd.com/'),('4khd-cosplay','https://www.4khd.com/pages/cosplay'),('4khd-album','https://www.4khd.com/pages/album'),('pornpics-home','https://www.pornpics.com/'),('celeb-hot','https://celeb.st/feed/hot')]:
 run(name,url)
