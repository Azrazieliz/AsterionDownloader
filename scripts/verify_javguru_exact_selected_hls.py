#!/usr/bin/env python3
"""Actual selected Jav Guru WordPress LU shortcode => first-party relay =>
selected original hoster => signed HLS playlist, no guessed video/ads.

All selected-player relations come from publisher HTML. No access gate bypass.
Output only host / range / playlist evidence, never ephemeral media URLs.
"""
import base64,html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_roshy_selected_packed_hls import Script,deobfuscate
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
WATCH="https://jav.guru/1077002/doks-695-little-devil-jk-gives-thick-tongue-kisses-while-repeatedly-teasing-and-toying-with-you-in-a-mean-slow-handjob-until-you-cum/"
class Doc(HTMLParser):
 def __init__(self):super().__init__();self.nodes={};self.buttons=[];self.ins=None;self.code=[];self.button=None
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if a.get("id"):self.nodes[a["id"]]=a
  if t=="a" and "wp-btn-iframe__shortcode" in a.get("class",""):
   self.button={"key":a.get("data-localize",""),"label":""}
  if t=="script" and not a.get("src"):self.ins=""
 def handle_data(self,s):
  if self.button:self.button["label"]+=s[:120]
  if self.ins is not None and len(self.ins)<500000:self.ins+=s
 def handle_endtag(self,t):
  if t=="a" and self.button:
   self.buttons.append(self.button);self.button=None
  if t=="script" and self.ins is not None:
   self.code.append(self.ins);self.ins=None
def req(url,ref):
 headers={"User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"}
 return urllib.request.Request(url,headers=headers)
def get(url,ref,limit=700000):
 with urllib.request.urlopen(req(url,ref),timeout=22) as res:
  return res.status,res.url,res.read(limit).decode("utf8","replace")
def value(js,key):
 m=re.search(r"\b"+re.escape(key)+r"\s*:\s*['\"]([^'\"]{1,160})['\"]",js)
 return m.group(1) if m else None
def main():
 status,url,page=get(WATCH,WATCH)
 doc=Doc();doc.feed(page)
 buttons=[b for b in doc.buttons if b["label"].strip().upper()=="STREAM LU"]
 assert len(buttons)==1,("selected LU shortcode button ambiguous",len(buttons))
 key=buttons[0]["key"]
 matches=[]
 for js in doc.code:
  for m in re.finditer(r"\bvar\s+"+re.escape(key)+r"\s*=\s*(\{[^}]{20,1000}\})",js,re.S):
   try:
    cfg=json.loads(m.group(1));encoded=cfg["iframe_url"]
    selected=base64.b64decode(encoded,validate=True).decode("utf8")
    p=urllib.parse.urlsplit(selected)
    if p.scheme=="https" and p.hostname=="jav.guru" and p.path=="/searcho/" and p.query.startswith("ed="):
     matches.append(selected)
   except Exception:continue
 assert len(set(matches))==1,"No attributable selected LU relay"
 relay=matches[0]
 print(json.dumps({"stage":"SELECTED_LU_PUBLISHER", "http":status,"unique_relay":True,
   "stream_buttons":len(doc.buttons)}),flush=True)
 status,_,relay_html=get(relay,WATCH)
 relay_doc=Doc();relay_doc.feed(relay_html)
 scripts=[s for s in relay_doc.code if "window.cfg" in s and "fullToken.rev()" in s]
 assert len(scripts)==1,("No unique selected publisher relay JS",len(scripts))
 source=scripts[0]
 cid=value(source,"cid");base=value(source,"base");rtype=value(source,"rtype")
 keys=re.search(r"\bkeys\s*:\s*\[\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\]",source)
 assert cid in relay_doc.nodes and keys and base=="https://jav.guru/searcho/" and rtype in ("e","v","s")
 container=relay_doc.nodes[cid]
 parts=[container.get(v) for v in keys.groups()]
 assert all(v and re.fullmatch(r"[A-Za-z0-9]{5,55}",v) for v in parts)
 target=base+"?"+rtype+"r="+"".join(parts)[::-1]
 status,final,player_html=get(target,relay,950000)
 p=urllib.parse.urlsplit(final)
 print(json.dumps({"stage":"ACTUAL_SELECTED_LULU_EMBED",
  "http":status,"final_host":p.hostname,"path_shape":
  "/e/{publisher-id}" if re.fullmatch(r"/e/[A-Za-z0-9_-]{5,180}/?",p.path) else "other",
  "html_bytes":len(player_html),
  "packed_scripts":player_html.count("eval(function(p,a,c,k,e,d)")}),flush=True)
 if p.hostname not in ("streamhihi.com","lulustream.com") or not re.fullmatch(r"/e/[A-Za-z0-9_-]{5,180}/?",p.path):
  return 2
 scripts=Script();scripts.feed(player_html)
 urls=[]
 for source in scripts.raw:
  if "eval(function(p,a,c,k,e,d)" not in source:continue
  decoded=deobfuscate(source)
  if "jwplayer" not in decoded or "sources:" not in decoded:continue
  for m in re.finditer(r"""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"](https://[^'"\s<>\\]{12,2000})['"]""",decoded,re.I):
   media=m.group(1).replace("\\/","/")
   u=urllib.parse.urlsplit(media)
   if (u.scheme=="https" and u.hostname and
       (u.hostname=="tnmr.org" or u.hostname.endswith(".tnmr.org") or
       u.hostname=="lulucdn.com" or u.hostname.endswith(".lulucdn.com")) and
       u.path.endswith("/master.m3u8") and u.query):
    urls.append(media)
 print(json.dumps({"stage":"EXACT_SELECTED_HLS_DISCOVERY","candidates":len(set(urls))}),flush=True)
 if len(set(urls))!=1:return 3
 hls=urls[0]
 with urllib.request.urlopen(req(hls,final),timeout=20) as response:
  content=response.read(160000)
  print(json.dumps({"stage":"ACTUAL_SELECTED_HLS_BYTES",
    "http":response.status,"mime":response.headers.get("Content-Type","").split(";")[0],
    "playlist_bytes":len(content),"extm3u":content.lstrip().startswith(b"#EXTM3U"),
    "variants":content.count(b"#EXT-X-STREAM-INF"),"host":urllib.parse.urlsplit(hls).hostname}),flush=True)
  return 0 if response.status==200 and content.lstrip().startswith(b"#EXTM3U") else 4
if __name__=="__main__":
 try:sys.exit(main())
 except urllib.error.HTTPError as err:
  print(json.dumps({"stage":"PUBLISHER_ACCESS_DENIED","http":err.code,
    "host":urllib.parse.urlsplit(err.url).hostname}),flush=True)
  sys.exit(5)
 except Exception as err:
  print(json.dumps({"stage":"EXACT_JAV_GURU_UNVERIFIED",
    "error_type":type(err).__name__,"reason":str(err)[:180]}),flush=True)
  sys.exit(6)
