#!/usr/bin/env python3
"""Check the exact user-shown NSFS-524 Roshy selected article and HLS player.

No guessed video host; first discover a genuine publisher /video/ article
containing the exact recorded NSFS-524 title token, then its ONLY selected
Beeteam368 player, then its Kamehaus HLS4 playlist. No full transfer here.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
ROOT="https://roshy.tv/"
class Links(HTMLParser):
 def __init__(self):
  super().__init__();self.links=[];self.active=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):
   self.active={"href":a["href"],"text":""}
   self.links.append(self.active)
 def handle_data(self,text):
  if self.active and len(self.active["text"])<300:
   self.active["text"]+=text[:300]
 def handle_endtag(self,tag):
  if tag=="a":self.active=None
def get(url,ref=ROOT,limit=1500000):
 req=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,
  "Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml,*/*"})
 with urllib.request.urlopen(req,timeout=24) as resp:
  return resp.status,resp.url,resp.read(limit).decode("utf8","replace")
def main():
 status,url,body=get("https://roshy.tv/?s=NSFS-524")
 assert status==200
 p=Links();p.feed(body)
 articles=[]
 for a in p.links:
  uri=urllib.parse.urlsplit(urllib.parse.urljoin(url,html.unescape(a["href"])))
  if uri.scheme=="https" and uri.hostname in ("roshy.tv","www.roshy.tv") and (
    re.search(r"^/video/[^/?#]*nsfs-524[^/?#]*/?$",uri.path,re.I) or
    "NSFS-524" in a["text"].upper()):
   if uri.path.startswith("/video/") and uri.geturl() not in articles:
    articles.append(uri.geturl())
 print(json.dumps({"stage":"PUBLISHER_SITE_SEARCH",
  "http":status,"exact_nsfs524_article_candidates":len(articles)}),flush=True)
 for article in articles[:3]:
  code,_,page=get(article)
  post=re.findall(r"id=['\"](beeteam368_player_[0-9]+)['\"]",page,re.I)
  post=list(dict.fromkeys(post))
  iframe=[]
  for id in post:
   m=re.search(r"jQuery\(\s*['\"]#"+re.escape(id)+
      r"['\"]\s*\)\s*\.beeteam368_pro_player\(\s*",page)
   if not m:continue
   try:cfg,_=json.JSONDecoder().raw_decode(page[m.end():].lstrip())
   except Exception:continue
   frames=re.findall(r"<iframe[^>]+src=['\"]([^'\"]+)",cfg.get("video_url",""),re.I)
   for f in frames:
    link=urllib.parse.urlsplit(urllib.parse.urljoin(article,html.unescape(f)))
    if link.scheme=="https" and link.hostname=="kamehaus.net" and (
       re.fullmatch(r"/e/[a-zA-Z0-9_-]{6,80}/?",link.path)):
     iframe.append(link.geturl())
  print(json.dumps({"stage":"SELECTED_ARTICLE",
    "selected_article_path":urllib.parse.urlsplit(article).path,
    "http":code,"player_containers":len(post),
    "exact_kamehaus_embeds":len(set(iframe))}),flush=True)
  if len(set(iframe))!=1:continue
  frame=iframe[0]
  code,_,markup=get(frame,article,300000)
  print(json.dumps({"stage":"SELECTED_KAMEHAUS_PUBLIC_PLAYER",
    "http":code,"player_html_bytes":len(markup),
    "packed_jwplayer_count":markup.count("eval(function(p,a,c,k,e,d)")}),flush=True)
  import verify_roshy_selected_packed_hls as verifier
  scripts=verifier.Script();scripts.feed(markup)
  valid=[verifier.deobfuscate(raw) for raw in scripts.raw
    if "eval(function(p,a,c,k,e,d)" in raw]
  if len(valid)!=1 or not valid[0]:return 4
  decoded=valid[0]
  selected_id=urllib.parse.urlsplit(frame).path.strip("/").split("/")[-1]
  if "tt"+selected_id not in decoded:return 5
  config=re.search(r"\bvar\s+links\s*=\s*",decoded)
  if not config:return 6
  tracks,_=json.JSONDecoder().raw_decode(decoded[config.end():].lstrip())
  available=[]
  for quality in ("hls4","hls2","hls3"):
    value=tracks.get(quality,"")
    if not isinstance(value,str) or not value:continue
    link=urllib.parse.urljoin(frame,value)
    uri=urllib.parse.urlsplit(link)
    publisher_first_party=(uri.scheme=="https" and uri.hostname=="kamehaus.net" and
      re.fullmatch(r"/stream/[A-Za-z0-9_/-]{10,250}/master\.m3u8",uri.path))
    publisher_cdn=(uri.scheme=="https" and uri.hostname and
      uri.hostname.endswith(".premilkyway.com") and
      uri.path.startswith("/hls2/") and uri.path.endswith(".m3u8") and
      bool(uri.query))
    print(json.dumps({"stage":"NSFS524_PUBLISHED_TRACK",
      "quality":quality,"host":uri.hostname,
      "path_prefix":uri.path.split("/")[:3],
      "m3u8":uri.path.endswith(".m3u8"),"signed_query":bool(uri.query),
      "strict_publisher_attribution":bool(publisher_first_party or publisher_cdn)}),flush=True)
    if publisher_first_party or publisher_cdn:
      available.append((quality,link))
  if not available:return 7
  for quality,source in available:
    try:
      with urllib.request.urlopen(urllib.request.Request(source,headers={
        "User-Agent":UA,"Referer":frame,
        "Accept-Encoding":"identity"}),timeout=23) as response:
        payload=response.read(25000)
        valid_hls=response.status==200 and payload.lstrip().startswith(b"#EXTM3U")
        print(json.dumps({"stage":"EXACT_NSFS524_SIGNED_HLS",
          "quality":quality,"http":response.status,
          "valid_hls":valid_hls,"master_bytes":len(payload),
          "variant_count":payload.count(b"#EXT-X-STREAM-INF"),
          "mime":response.headers.get("Content-Type","").split(";")[0]}),flush=True)
        if valid_hls:return 0
    except Exception as error:
      print(json.dumps({"stage":"EXACT_NSFS524_SIGNED_HLS_ERROR",
        "quality":quality,"type":type(error).__name__,
        "reason":str(error)[:80]}),flush=True)
  return 8
 return 2
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as err:
  print(json.dumps({"status":"NSFS524_SELECTED_PROBE_FAILED",
    "type":type(err).__name__,"reason":str(err)[:180]}),flush=True)
  raise SystemExit(3)
