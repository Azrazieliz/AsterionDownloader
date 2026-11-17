#!/usr/bin/env python3
"""Authoritative selected HentaiHaven series/episode links and NHPlayer's
public click handler. No guess/download, credentials, media URLs or CAPTCHA.
"""
import html,json,re,urllib.request,urllib.parse,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
HAVEN="https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/"
NH="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
class Anchors(HTMLParser):
 def __init__(self):super().__init__();self.links=[];self.divs=[];self.inline=[];self.in_script=False;self.script=""
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="a" and a.get("href"):self.links.append(a["href"])
  if t=="div" and ("play" in a.get("class","") or "player" in a.get("class","")):
   self.divs.append({"class":a.get("class","")[:120],"attrs":list(a)[:14]})
  if t=="script" and not a.get("src"):self.in_script=True;self.script=""
 def handle_data(self,d):
  if self.in_script and len(self.script)<100000:self.script+=d
 def handle_endtag(self,t):
  if t=="script" and self.in_script:self.inline.append(self.script);self.in_script=False
def read(url,referer):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Accept-Encoding":"identity","Referer":referer})
 with urllib.request.urlopen(req,timeout=20) as r:
  return r.status,r.read(850000).decode("utf8","replace")
def scrub(s):
 s=re.sub(r"https?://[^\s\"'<>]+","[url]",s)
 s=re.sub(r"[A-Za-z0-9_-]{30,}","[opaque]",s)
 return s[:2300]
try:
 code,htmlpage=read(HAVEN,HAVEN);p=Anchors();p.feed(htmlpage)
 page=urllib.parse.urlsplit(HAVEN);base=page.path.rstrip("/")
 anchors=list(dict.fromkeys(urllib.parse.urlsplit(urllib.parse.urljoin(HAVEN,href)).path
  for href in p.links if urllib.parse.urlsplit(urllib.parse.urljoin(HAVEN,href)).hostname==page.hostname))
 selected=[link for link in anchors if link.startswith(base+"/")]
 episodes=[link for link in selected if re.fullmatch(re.escape(base)+r"/episode-[0-9]+/?",link)]
 print(json.dumps({"site":"hentaihaven","status":code,"selected_route":base,
  "watch_links_in_page":len([x for x in anchors if x.startswith("/watch/")]),
  "selected_child_routes":len(selected),"episode_routes":len(episodes),
  "episode_path_shapes":[x.replace(base,"/watch/{series}") for x in episodes[:8]],
  "series_page_has_nextjs_flight":"self.__next_f.push" in htmlpage,
  "nextjs_episode_markers":len(re.findall(r"episodeTitle|episodeSlug|episodeNumber|episodeUrl",htmlpage)),
  "video_keyword_counts":{k:htmlpage.lower().count(k) for k in
    ("playbackurl","embedurl","streamurl","videourl","hls","m3u8","mp4","videoid")}
 }),flush=True)
 if episodes:
  episode=urllib.parse.urljoin(HAVEN,episodes[0]);status,markup=read(episode,HAVEN)
  q=Anchors();q.feed(markup)
  print(json.dumps({"site":"hentaihaven","stage":"PUBLISHED_SELECTED_EPISODE",
   "http":status,"body_bytes":len(markup),"nextjs_flight":"self.__next_f.push" in markup,
   "video_keyword_counts":{k:markup.lower().count(k) for k in
    ("playbackurl","embedurl","streamurl","videourl","hls","m3u8","mp4","videoid")},
   "player_nodes":q.divs[:8],
   "first_party_episode_path":urllib.parse.urlsplit(episode).path.replace(base,"/watch/{series}")}),flush=True)
except Exception as e:print(json.dumps({"site":"hentaihaven","error":type(e).__name__,"reason":str(e)[:150]}),flush=True)
try:
 code,markup=read(NH,NH);p=Anchors();p.feed(markup)
 print(json.dumps({"site":"nhplayer","http":code,"player_nodes":p.divs[:12],
  "inline_lengths":[len(x) for x in p.inline[:10]],
  "inline_player_snippets":[scrub(x[:1200]) for x in p.inline if
    "player" in x.lower() or "iframe" in x.lower()][:3],
  "page_play_div_context":scrub(markup[max(0,markup.find("p-pulse")-300):markup.find("p-pulse")+450])
 }),flush=True)
 status,js=read("https://nhplayer.com/content/themes/_app.js",NH)
 print(json.dumps({"site":"nhplayer","stage":"SELECTED_FIRST_PARTY_CLICK_HANDLER",
  "http":status,"script_length":len(js),"code":scrub(js)}),flush=True)
except Exception as e:print(json.dumps({"site":"nhplayer","error":type(e).__name__,"reason":str(e)[:150]}),flush=True)
