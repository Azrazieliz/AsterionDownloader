#!/usr/bin/env python3
"""Read-only selected player contract inspection. No media downloaded."""
import urllib.request,urllib.parse,urllib.error,html.parser,re,json
sites=[
("pimpbunny.com","https://pimpbunny.com/",r"^/videos/"),
("xchina.co","https://en.xchina.co/",r"^/video/[a-f0-9]{8,30}"),
("bubblecum.lol","https://www.bubblecum.lol/",r"^/video/[a-z0-9-]{6,}"),
("pornavhd.com","https://pornavhd.com/",r"^/20\d{2}/\d{1,2}/\d{1,2}/"),
("camstreams.tv","https://camstreams.tv/latest-updates/",r"^/videos/\d+/")]
class Cards(html.parser.HTMLParser):
 def __init__(self,b):super().__init__();self.base=b;self.links=[];self.frames=[];self.sources=[]
 def handle_starttag(self,tag,attrs):
  d=dict(attrs)
  if tag=="a" and d.get("href"):self.links.append(urllib.parse.urljoin(self.base,d["href"]))
  if tag=="iframe" and d.get("src"):self.frames.append(urllib.parse.urljoin(self.base,d["src"]))
  if tag in ("video","source") and (d.get("src") or d.get("data-src")):
   self.sources.append(urllib.parse.urljoin(self.base,d.get("src") or d.get("data-src")))
def fetch(url,referer=None):
 h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"}
 if referer:h["Referer"]=referer
 with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=15) as r:
  return r.url,r.read(950000).decode("utf-8","replace")
def short(url):
 try:
  u=urllib.parse.urlsplit(url)
  return (u.hostname or "")+u.path[:120]
 except:return "invalid"
for host,root,pattern in sites:
 out={"host":host}
 try:
  real,page=fetch(root);p=Cards(real);p.feed(page)
  watched=list(dict.fromkeys(u for u in p.links if
   urllib.parse.urlsplit(u).hostname and
   host in urllib.parse.urlsplit(u).hostname and
   re.match(pattern,urllib.parse.urlsplit(u).path,re.I)))
  out["selected_routes"]=len(watched)
  if not watched:print(json.dumps(out),flush=True);continue
  selected,html=fetch(watched[0],real)
  q=Cards(selected);q.feed(html)
  out["selected_path"]=urllib.parse.urlsplit(selected).path
  out["frames"]=list(dict.fromkeys(map(short,q.frames)))[:8]
  out["native_sources"]=list(dict.fromkeys(map(short,q.sources)))[:8]
  urls=re.findall(r"""(?i)(?:https?:)?(?://)[^"'\s<>\\]{7,700}?(?:\.m3u8|\.mp4)(?:\?[^"'\s<>\\]{0,200})?""",html)
  out["html_named_media_hosts"]=list(dict.fromkeys(short(u) for u in urls))[:14]
  out["media_link_count"]=len(urls)
  snippets=[]
  for word in (".m3u8",".mp4","contentUrl","jwplayer(","player.src","sources:", "video_url","embed_url","file:","stream_url","var sources"):
   mo=re.search(re.escape(word),html,re.I)
   if mo is not None:
    section=html[max(0,mo.start()-100):min(len(html),mo.end()+100)]
    # Avoid exporting CDN signatures, tokens or personally identifying titles.
    section=re.sub(r"""https?://[^\s"'<>]{15,}""","<URL>",section)
    section=re.sub(r"[A-Za-z0-9_-]{50,}","<opaque>",section)
    snippets.append([word,re.sub(r"\s+"," ",section)[:150]])
  out["script_clues"]=snippets[:12]
  print(json.dumps(out,ensure_ascii=False),flush=True)
 except Exception as e:
  out["error"]=type(e).__name__;out["detail"]=str(e)[:100]
  print(json.dumps(out),flush=True)
