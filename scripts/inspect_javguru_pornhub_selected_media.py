#!/usr/bin/env python3
"""Selected Jav Guru shortcode relay and Pornhub selected watch player media.

Bounded public official GETs. No CAPTCHA/login bypass. No guessing media URLs.
Print path/host/format/counts, NOT opaque IDs, signed URLs or user cookies.
"""
import base64,html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
GURU="https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/"
PH_HOME="https://www.pornhub.com/"
class Page(HTMLParser):
 def __init__(self):super().__init__();self.stack=[];self.buttons=[];self.js=[];self.data=None;self.anchors=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="a" and a.get("href"):self.anchors.append(a["href"])
  if t=="a" and "wp-btn-iframe__shortcode" in a.get("class",""):
   self.buttons.append(a)
  if t=="script":
   if a.get("src"):self.js.append(("src",a["src"]))
   else:self.data=""
 def handle_data(self,s):
  if self.data is not None and len(self.data)<1_000_000:self.data+=s
 def handle_endtag(self,t):
  if t=="script" and self.data is not None:self.js.append(("inline",self.data));self.data=None
def get(url,ref=None,range_value=None,maxbytes=1_300_000):
 headers={"User-Agent":UA,"Accept-Encoding":"identity","Accept":"*/*"}
 if ref:headers["Referer"]=ref
 if range_value:headers["Range"]=range_value
 req=urllib.request.Request(url,headers=headers)
 try:
  with urllib.request.urlopen(req,timeout=22) as r:
   return {"http":r.status,"url":r.url,"mime":r.headers.get("Content-Type","").split(";")[0],
           "range":r.headers.get("Content-Range","")},r.read(maxbytes)
 except urllib.error.HTTPError as e:
  return {"http":e.code,"url":e.url,"mime":e.headers.get("Content-Type","").split(";")[0]},e.read(200000)
def emit(**kw):print(json.dumps(kw),flush=True)
def guru():
 status,body=get(GURU);raw=body.decode("utf8","replace")
 p=Page();p.feed(raw)
 controls=[(a.get("data-localize"),a.get("class","")) for a in p.buttons]
 codes=[(m.group(1),m.group(2)) for typ,source in p.js if typ=="inline"
  for m in re.finditer(r"""var\s+([a-z]{10})\s*=\s*(\{[^}]{20,800}\})\s*;""",source,re.S)]
 variants=[]
 for key,value in codes:
  if key not in {k for k,_ in controls}:continue
  try:
   obj=json.loads(value); url=base64.b64decode(obj["iframe_url"],validate=True).decode()
   u=urllib.parse.urlsplit(url)
   if (u.scheme=="https" and u.hostname in ("jav.guru","www.jav.guru") and
       u.path=="/searcho/" and u.query.startswith(("ed=","vd=","sr=","vr="))):
    variants.append((key,url))
  except Exception:continue
 emit(provider="Jav Guru",stage="selected_wordpress_shortcode",
  http=status["http"],buttons=len(controls),published_matching_wrappers=len(variants),
  no_static_searcho_plaintext=True)
 for key,url in variants[:2]:
  reply,body=get(url,GURU,maxbytes=900000)
  raw=body.decode("utf8","replace")
  names=re.findall(r"\b(?:cid|base|rtype|keys)\s*:",raw)[:12]
  emit(provider="Jav Guru",stage="exact_first_party_relay",
   http=reply["http"],redirected_host=urllib.parse.urlsplit(reply["url"]).hostname,
   html_bytes=len(raw),cfg_keys=names,
   token_parts=len(re.findall(r"""data-[a-z0-9]{3,20}=["'][a-z0-9]{5,55}["']""",raw)),
   script_packer="eval(function(p,a,c,k,e,d)" in raw,
   gate=bool(re.search("just a moment|verify you are human|captcha",raw[:12000],re.I)))
def ph():
 status,body=get(PH_HOME);p=Page();p.feed(body.decode("utf8","replace"))
 watch=None
 for link in p.anchors:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(PH_HOME,html.unescape(link)))
  if u.hostname not in ("www.pornhub.com","pornhub.com") or u.path!="/view_video.php":continue
  v=urllib.parse.parse_qs(u.query).get("viewkey",[""])[0]
  if re.fullmatch(r"[A-Za-z0-9_-]{5,50}",v):
   watch=u.geturl();break
 if not watch:return emit(provider="Pornhub",status="NO_EXACT_PUBLISHED_WATCH")
 result,body=get(watch,PH_HOME)
 htmlpage=body.decode("utf8","replace")
 p=Page();p.feed(htmlpage)
 fmt=[(n,txt) for n,(tag,txt) in enumerate(p.js) if tag=="inline" and
      ("mediaDefinitions" in txt or "flashvars_" in txt)]
 for index,text in fmt[:6]:
  # parser handles actual JSON object assigned to flashvars_<id>; no script
  # executes, and recommendations/advertising JSON cannot be promoted.
  candidates=[]
  for m in re.finditer(r"\b(?:var\s+)?flashvars_[0-9]{1,15}\s*=\s*",text):
   try:
    obj,length=json.JSONDecoder().raw_decode(text[m.end():].lstrip())
    if isinstance(obj,dict):candidates.append(obj)
   except Exception:continue
  for obj in candidates[:3]:
   defs=obj.get("mediaDefinitions",[])
   if not isinstance(defs,list):continue
   shapes=[]
   for d in defs[:20]:
    if not isinstance(d,dict):continue
    url=d.get("videoUrl")
    parsed=urllib.parse.urlsplit(str(url or ""))
    shapes.append({"format":d.get("format"),"quality":d.get("quality"),
      "host":parsed.hostname,"route_extension":parsed.path.rsplit(".",1)[-1][-7:] if "." in parsed.path else "",
      "path_kind":"media_api" if "/media/" in parsed.path else "direct_file",
      "keys":list(d.keys())[:9]})
   emit(provider="Pornhub",stage="selected_flashvars",
    selected_viewkey_valid=True,http=result["http"],script_index=index,
    config_properties=list(obj.keys())[:25],
    video_id_present=bool(obj.get("video_id") or obj.get("videoId")),
    media_definition_count=len(defs),media_formats=shapes[:10])
 emit(provider="Pornhub",stage="player_summary",http=result["http"],
  page_bytes=len(htmlpage),inline_configs=len(fmt))
if __name__=="__main__":
 for f in (guru,ph):
  try:f()
  except Exception as e:emit(provider=f.__name__,status="FAILED",
    error_type=type(e).__name__,reason=str(e)[:150])
