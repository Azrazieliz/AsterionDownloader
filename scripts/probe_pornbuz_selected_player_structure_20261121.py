#!/usr/bin/env python3
"""Public selected PornBuz active main-player embed structure, no media
downloads, no signed URLs or tokens in output; not a gateway bypass.
"""
import argparse,html.parser,json,pathlib,re,sqlite3,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from contextlib import closing
class Doc(html.parser.HTMLParser):
 def __init__(self,base):super().__init__();self.base=base;self.stack=[];self.frames=[];self.video=[];self.sources=[];self.scripts=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs);scope=" ".join(x[2] for x in self.stack[-7:])
  if tag=="iframe" and a.get("src"):self.frames.append((urllib.parse.urljoin(self.base,a["src"]),scope))
  if tag in ("video","source"):
   self.video.append((tag,a.get("src"),a.get("data-src"),a.get("duration"),scope[:150]))
  if tag=="script" and a.get("src"):
   self.scripts.append(urllib.parse.urljoin(self.base,a["src"]))
  if tag not in ("meta","img","source","link","input","br","hr"):self.stack.append((tag,a.get("id",""),a.get("class","")))
 def handle_endtag(self,tag):
  for i in range(len(self.stack)-1,-1,-1):
   if self.stack[i][0]==tag:del self.stack[i:];break
def fetch(url,referer=None):
 h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36","Accept-Encoding":"identity"}
 if referer:h["Referer"]=referer
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers=h),timeout=18) as res:
   return res.status,res.url,res.read(750000).decode("utf-8","replace")
 except urllib.error.HTTPError as e:return e.code,e.url,e.read(65000).decode("utf-8","replace")
def uri_shape(url):
 p=urllib.parse.urlsplit(url)
 return {"host":p.hostname,"ext":p.path.rsplit(".",1)[-1][:8] if "." in p.path else "",
     "path_segments":len(p.path.split("/")),"query":bool(p.query)}
p=argparse.ArgumentParser();p.add_argument("--device",required=True);p.add_argument("--package",default="com.azrael.pixivdumpsync.acceptance")
a=p.parse_args()
with tempfile.TemporaryDirectory(prefix="adl_pornbuz_main_") as d:
 root=pathlib.Path(d)
 for n in ("videoflow.sqlite","videoflow.sqlite-wal","videoflow.sqlite-shm"):
  z=subprocess.run(["adb","-s",a.device,"exec-out","run-as",a.package,"cat","databases/"+n],capture_output=True,timeout=20)
  if z.returncode==0 and z.stdout:(root/n).write_bytes(z.stdout)
 with closing(sqlite3.connect("file:"+str(root/"videoflow.sqlite")+"?mode=ro",uri=True)) as db:
  rows=db.execute("SELECT url FROM videos WHERE url LIKE '%pornbuz.com%' ORDER BY updated DESC LIMIT 7").fetchall()
 selected=next((u[0] for u in rows if urllib.parse.urlsplit(u[0]).hostname in ("pornbuz.com","www.pornbuz.com")),None)
 if not selected:raise RuntimeError("no selected post")
 code,final,html=fetch(selected);doc=Doc(final);doc.feed(html)
 frames=[url for url,scope in doc.frames if
    urllib.parse.urlsplit(url).hostname=="player.watchstreamus.com"
    and "responsive-player" in scope and "video-player" in scope]
 if len(set(frames))!=1:
  print(json.dumps({"stage":"ACTIVE_SOURCE_AMBIGUOUS","matches":len(set(frames))}));raise SystemExit(2)
 player=frames[0]
 if not re.fullmatch(r"/embed/[A-Za-z0-9_-]{5,150}/?",urllib.parse.urlsplit(player).path):
  raise RuntimeError("unexpected selected player route")
 ec,ef,body=fetch(player,selected);inner=Doc(ef);inner.feed(body)
 hrefs=re.findall(r"""(?i)(?:https?:)?//[^'"<>\\s]{10,2100}?(?:\.mp4|\.m3u8)(?:\\?[^'"<>\\s]{0,200})?""",body)
 print(json.dumps({"stage":"EXACT_SELECTED_MAIN_PLAYER","article_http":code,
  "article_frames":len(doc.frames),"selected_main_player_count":len(set(frames)),
  "player_host":urllib.parse.urlsplit(player).hostname,"player_http":ec,
  "player_final_host":urllib.parse.urlsplit(ef).hostname,"html_length":len(body),
  "nested_iframe_hosts":[uri_shape(u) for u,_ in inner.frames[:8]],
  "video_elements":inner.video[:8],"script_hosts":[uri_shape(u) for u in inner.scripts[:12]],
  "named_media_hosts":[uri_shape(u) for u in hrefs[:12]],
  "native_play_markers":{k:len(re.findall(re.escape(k),body,re.I)) for k in
    ("video-js","jwplayer","fluidplayer","player.src","hls.js","sources","file:","html5","video_url","duration")},
  "gate":bool(re.search(r"captcha|cf-turnstile|verify you are human|just a moment",body[:12000],re.I))
 }),flush=True)
 inline=re.findall(r"""(?is)<script(?![^>]*\bsrc=)[^>]*>(.*?)</script>""",body)
 for variable in ("VK_PLAYBACK","VK_PLAYER_OPTS","VK_ADS"):
  result=[]
  for code in inline:
   m=re.search(r"(?:window\.)?"+variable+r"\s*=\s*\{",code)
   if m:
    snippet=code[m.end()-1:m.end()+900]
    keys=re.findall(r"""(?i)(?:["']?)([a-z_][a-z0-9_]{1,40})(?:["']?)\s*:""",snippet)
    hosts=sorted(set(urllib.parse.urlsplit(u).hostname for u in
      re.findall(r"""https?://[^"'\s<>]{12,900}""",snippet)))
    result.append({"keys":keys[:24],"hosts":hosts,"chars":len(code),
      "hls_mentions":len(re.findall(r"\.m3u8",code,re.I)),
      "mp4_mentions":len(re.findall(r"\.mp4",code,re.I)),
      "has_file_key":bool(re.search(r"\bfile\s*:",code))})
  print(json.dumps({"variable":variable,"configs":result[:2]}),flush=True)

 # Exact JWPlayer article-owned PLAYBACK config, never advertising.
 for script in inline:
  marker=re.search(r"(?is)(?:window\.)?VK_PLAYBACK\s*=\s*",script)
  if marker is None:continue
  try:
   payload=json.JSONDecoder().raw_decode(script[marker.end():].lstrip())[0]
   value=payload.get("src") if isinstance(payload,dict) else None
   if not isinstance(value,str):raise ValueError("No selected playback source")
   media=urllib.parse.urljoin(ef,value);u=urllib.parse.urlsplit(media)
   parsed={"format":payload.get("format"),
     "source_host":u.hostname,"source_ext":u.path.rsplit(".",1)[-1][:10] if "." in u.path else "",
     "signed_query":bool(u.query),"copies":len(payload.get("copies") or []),
     "has_source":u.scheme=="https" and bool(u.hostname)}
   if u.scheme=="https" and u.hostname and u.path.endswith(".m3u8"):
    try:
     req=urllib.request.Request(media,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
       "Referer":ef,"Accept-Encoding":"identity"})
     with urllib.request.urlopen(req,timeout=18) as res:
      bodyText=res.read(170000).decode("utf-8","replace")
      parsed["manifest_status"]=res.status
      parsed["hls"]=bodyText.startswith("#EXTM3U")
      parsed["master_variants"]=bodyText.count("#EXT-X-STREAM-INF")
      parsed["segment_count"]=sum(bool(x.strip()) and not x.startswith("#") for x in bodyText.splitlines())
      parsed["endlist"]="#EXT-X-ENDLIST" in bodyText
      parsed["runtime_s"]=round(sum(float(x) for x in re.findall(r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",bodyText)),1)
      if parsed["master_variants"]==1:
       lines=[x.strip() for x in bodyText.splitlines()]
       variants=[lines[i+1] for i,x in enumerate(lines[:-1]) if x.startswith("#EXT-X-STREAM-INF:")]
       child=urllib.parse.urljoin(media,variants[0])
       ch=urllib.parse.urlsplit(child)
       if (ch.scheme=="https" and ch.hostname==u.hostname and
           ch.path.endswith(".m3u8") and not ch.fragment):
        try:
         request=urllib.request.Request(child,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
           "Referer":media,"Accept-Encoding":"identity"})
         with urllib.request.urlopen(request,timeout=18) as sub:
          childBody=sub.read(220000).decode("utf8","replace")
          parsed["child_http"]=sub.status
          parsed["child_hls"]=childBody.startswith("#EXTM3U")
          parsed["child_host"]=urllib.parse.urlsplit(sub.url).hostname
          parsed["child_segments"]=sum(bool(v.strip()) and not v.startswith("#") for v in childBody.splitlines())
          parsed["child_endlist"]="#EXT-X-ENDLIST" in childBody
          parsed["child_runtime_s"]=round(sum(float(x) for x in re.findall(r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",childBody)),1)
        except urllib.error.HTTPError as e:parsed["child_http"]=e.code
        except Exception as e:parsed["child_error"]=type(e).__name__
    except urllib.error.HTTPError as e:parsed["manifest_status"]=e.code
    except Exception as e:parsed["manifest_error"]=type(e).__name__
   print(json.dumps({"stage":"EXACT_VK_PLAYBACK_HLS","data":parsed}),flush=True)
  except Exception as e:
   print(json.dumps({"stage":"EXACT_VK_PLAYBACK_UNRESOLVED","error":type(e).__name__}),flush=True)
  break
