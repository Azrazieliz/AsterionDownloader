#!/usr/bin/env python3
"""Live, publisher-selected player contracts for Jav Guru, JAVIDEO, HelloPorn,
and Pornhub. Public HTML only + exact selected VOE embed metadata when available.
Never use related/advertising video, guess a media URL or bypass access gates.
Opaque signed URLs and subscriber details are not printed.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error,zipfile
from html.parser import HTMLParser
from pathlib import Path

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
TARGETS={
 "javguru":"https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/",
 "javideo":"https://javideo.net/en/fc2ppv-4994603",
 "helloporn":"https://helloporn.com/video-74763-nurumassage-170407privatepracticemassage-s01-ericmasterson-summerday-j",
 "pornhub":"https://www.pornhub.com/",
}
class HTML(HTMLParser):
 def __init__(self):
  super().__init__();self.stack=[];self.frames=[];self.elements=[];self.js=[];self.inline=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);ancestor=" ".join(v[1] for v in self.stack[-7:])
  if tag=="iframe" and a.get("src"):
   self.frames.append((a["src"],ancestor))
  if tag in ("video","source","meta","button","a"):
   self.elements.append((tag,a,ancestor))
  if tag=="script":
   if a.get("src"):self.js.append(("src",a["src"]))
   else:self.inline=""
  if tag not in ("img","input","meta","source","br","link","hr","area","wbr"):
   self.stack.append((tag,a.get("id","")+" "+a.get("class","")))
 def handle_data(self,txt):
  if self.inline is not None and len(self.inline)<250000:self.inline+=txt
 def handle_endtag(self,tag):
  if tag=="script" and self.inline is not None:self.js.append(("inline",self.inline));self.inline=None
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
def open_page(url,referer=None):
 h={"User-Agent":UA,"Accept-Encoding":"identity","Accept-Language":"en-US,en;q=0.9"}
 if referer:h["Referer"]=referer
 request=urllib.request.Request(url,headers=h)
 try:
  with urllib.request.urlopen(request,timeout=22) as r:
   return r.status,r.url,r.headers.get("Content-Type","").split(";")[0],r.read(1300000).decode("utf8","replace")
 except urllib.error.HTTPError as ex:
  return ex.code,ex.url,ex.headers.get("Content-Type","").split(";")[0],ex.read(150000).decode("utf8","replace")
def emit(**kw):print(json.dumps(kw,ensure_ascii=False),flush=True)
def guru():
 for selected in [TARGETS["javguru"],
  "https://jav.guru/1076811/mukc-135-off-pako-creampie-breeding-photo-session-where-we-defile-a-beautiful-pure-idol-whos-obedient-like-a-doll-however-we-please-shitara-yuuhi/"]:
  code,final,mime,body=open_page(selected)
  p=HTML();p.feed(body)
  labels=[a.get("data-localize","") for tag,a,scope in p.elements
   if tag=="a" and "wp-btn-iframe__shortcode" in a.get("class","") and "post-" in scope]
  emit(provider="Jav Guru",selected_path=urllib.parse.urlsplit(selected).path[:40],
   http=code,mime=mime,bytes=len(body),shortcode_controls=len(labels),
   first_party_searcho_mentions=body.count("/searcho/"),
   challenge=bool(re.search("just a moment|verify you are human|cf-turnstile",body[:20000],re.I)))
def javideo():
 url=TARGETS["javideo"];code,final,mime,body=open_page(url)
 p=HTML();p.feed(body)
 active=[urllib.parse.urlsplit(urllib.parse.urljoin(url,s)).hostname
         for s,ancestor in p.frames if "player" in ancestor]
 player_js=[v for t,v in p.js if t=="inline" and re.search(r"clickfakeplayer|streamtape|sbtape",v,re.I)]
 emit(provider="JAVIDEO",selected_route=urllib.parse.urlsplit(url).path,
  http=code,mime=mime,bytes=len(body),player_frames=active[:8],
  page_clickfakeplayer=body.count("clickfakeplayer"),
  streamtape_mentions=body.lower().count("streamtape"),
  scripts_for_selected_player=len(player_js),
  gate=bool(re.search("turnstile|captcha|verify you are human|just a moment",body[:40000],re.I)))
def helloporn():
 url=TARGETS["helloporn"];code,final,mime,body=open_page(url)
 p=HTML();p.feed(body)
 frames=[]
 for raw,scope in p.frames:
  v=urllib.parse.urlsplit(urllib.parse.urljoin(final,html.unescape(raw)))
  if v.scheme=="https" and v.hostname=="voe.sx" and re.fullmatch(r"/e/[A-Za-z0-9_-]{6,80}/?",v.path):
   if re.search("player|article|main",scope,re.I):frames.append(v.geturl())
 frames=list(dict.fromkeys(frames))
 emit(provider="HelloPorn",selected_route=urllib.parse.urlsplit(url).path,
  http=code,mime=mime,bytes=len(body),voe_exact_selected_frames=len(frames),
  all_iframe_count=len(p.frames),video_element_count=len([1 for t,_,_ in p.elements if t=="video"]))
 if len(frames)!=1:return
 frame=frames[0]
 code,final,mime,player=open_page(frame,url)
 q=HTML();q.feed(player)
 emit(provider="HelloPorn",stage="selected_VOE_player",http=code,
  final_host=urllib.parse.urlsplit(final).hostname,mime=mime,
  html_bytes=len(player),frame_count=len(q.frames),
  video_elements=len([1 for t,_,_ in q.elements if t=="video"]),
  direct_mp4_literals=len(re.findall(r"\.mp4(?:\?|[\"'])",player,re.I)),
  hls_literals=len(re.findall(r"\.m3u8(?:\?|[\"'])",player,re.I)))
 if code!=200:return
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 try:
  with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,"skip_download":True,"noplaylist":True,
    "socket_timeout":18,"retries":0,
    "http_headers":{"User-Agent":UA,"Referer":url}}) as y:
   meta=y.extract_info(frame,download=False)
  fmts=[f for f in meta.get("formats",[]) if f.get("url") and f.get("vcodec")!="none"]
  hosts=sorted(set(urllib.parse.urlsplit(f["url"]).hostname for f in fmts))
  emit(provider="HelloPorn",stage="exact_selected_VOE_original",
   extractor="app_bundled_yt_dlp",result="METADATA_ONLY_NOT_COMPLETE",
   duration=meta.get("duration"),title_match=bool(meta.get("title")),
   video_formats=len(fmts),media_hosts=hosts[:5],
   extensions=sorted(set(f.get("ext","") for f in fmts)))
 except Exception as ex:
  emit(provider="HelloPorn",stage="exact_selected_VOE_original",
   result="EXTRACTOR_UNRESOLVED",error_class=type(ex).__name__,
   error=str(ex)[:140])
def pornhub():
 url=TARGETS["pornhub"];code,final,_,body=open_page(url)
 p=HTML();p.feed(body)
 selected=None
 for tag,a,scope in p.elements:
  if tag!="a":continue
  link=urllib.parse.urlsplit(urllib.parse.urljoin(url,html.unescape(a.get("href",""))))
  if link.hostname not in ("www.pornhub.com","pornhub.com") or link.path!="/view_video.php":continue
  pairs=urllib.parse.parse_qs(link.query)
  viewkey=pairs.get("viewkey",[""])[0]
  if re.fullmatch(r"[A-Za-z0-9_-]{5,50}",viewkey):
   selected=link.geturl();break
 if selected is None:
  emit(provider="Pornhub",stage="selected_discovery",http=code,
   result="NO_SELECTED_ARTICLE_WITH_VIEWKEY");return
 code,final,mime,source=open_page(selected,url)
 q=HTML();q.feed(source)
 scripts=[v for t,v in q.js if t=="inline"]
 flash=[v for v in scripts if "mediaDefinitions" in v or
  re.search(r"flashvars_[0-9]+",v)]
 config=[v for v in flash if "mediaDefinitions" in v]
 controls=[(t,a.get("src","") or a.get("data-src",""),scope)
   for t,a,scope in q.elements if t in ("video","source") and re.search("player",scope,re.I)]
 emit(provider="Pornhub",stage="exact_selected_watch",http=code,mime=mime,
  selected_route="/view_video.php?viewkey={selected}",html_bytes=len(source),
  selected_viewkey_valid=True,player_videos=len(controls),flashvars_scripts=len(flash),
  selected_media_definitions=len(config),
  player_unavailable=bool(re.search(r"video.{0,25}(?:removed|unavailable|not available)",source[:80000],re.I)),
  verification_gate=bool(re.search("just a moment|verify you are human|cf-turnstile",source[:40000],re.I)))
 if len(config)==1:
  script=config[0]
  types=sorted(set(re.findall(r"""["']format["']\s*:\s*["']([^"']{1,25})""",script)))[:9]
  emit(provider="Pornhub",stage="player_media_definition_structure",
   script_bytes=len(script),published_formats=types,
   apparent_quality_count=len(re.findall(r"\bquality\s*:",script)),
   opaque_media_urls_not_logged=True)
if __name__=="__main__":
 for fn in (guru,javideo,helloporn,pornhub):
  try:fn()
  except Exception as e:emit(provider=fn.__name__,stage="error",
    error_class=type(e).__name__,reason=str(e)[:170])
