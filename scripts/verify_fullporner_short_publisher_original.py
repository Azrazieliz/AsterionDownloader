#!/usr/bin/env python3
"""Real FullPorner source-to-PLAYABLE original on a second public selected item.

Previously attested selected ID 9509933's 400MB stream got only 67MB/388sec;
8-way ranges returned 503. To determine whether THIS provider can deliver a
WHOLE ORIGINAL, discover up to 12 public /watch/<24hex> pages from publisher's
own homepage, bind each to exactly one /player/<id>/<mask>, parse publisher
first-party reversal+quality script, require actual MP4 ftyp+Content-Range,
choose a short publisher work (<120MB), then download every byte and FFprobe.
Do not replace the known failing item or mark ALL works; one representative
full file is not site-wide reliability. Temporary media deleted afterwards.
"""
import concurrent.futures,html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from verify_nine_exact_original_full_files import req,get_html,full_verify
PUBLISHER="https://fullporner.com/"
class Listings(HTMLParser):
 def __init__(self):super().__init__();self.pages=[];self.frames=[]
 def handle_starttag(self,t,attrs):
  a=dict(attrs)
  if t=="a" and a.get("href"):self.pages.append(a["href"])
  if t=="iframe" and a.get("src"):self.frames.append(a["src"])
def site(url,referer=None):
 with urllib.request.urlopen(req(url,referer),timeout=22) as r:
  assert r.status==200
  return r.read(1800000).decode("utf-8","replace")
def inspect(p):
 try:
  article=site(p,PUBLISHER)
  q=Listings();q.feed(article)
  players=[]
  for raw in q.frames:
   u=urllib.parse.urlsplit(urllib.parse.urljoin(p,html.unescape(raw)))
   if u.scheme=="https" and u.hostname in ("fullporner.com","www.fullporner.com") and
      re.fullmatch(r"/player/[0-9]{1,12}/[0-9]{1,4}/?",u.path):
    players.append(u.geturl())
  players=list(dict.fromkeys(players))
  if len(players)!=1:return {"selected":urllib.parse.urlsplit(p).path,"error":"ambiguous_player"}
  player=players[0]
  script=site(player,p)
  route=re.fullmatch(r"/player/([0-9]{1,12})/([0-9]{1,4})/?",
      urllib.parse.urlsplit(player).path)
  a=re.search(r"""\bvar\s+id\s*=\s*['"]([0-9]{1,12})['"]\s*\.split\(['"]{2}\)\s*\.reverse\(\)\s*\.join\(['"]{2}\)""",script,re.I)
  b=re.search(r"""\bvar\s+quality\s*=\s*parseInt\(['"]([0-9]{1,4})['"]\)""",script,re.I)
  if not route or not a or not b or (a.group(1),b.group(1))!=(
        route.group(1),route.group(2)):
   return {"selected":urllib.parse.urlsplit(p).path,"error":"publisher_identity"}
  mask=int(b.group(1))
  qualities=[n for n,bit in ((360,1),(480,2),(720,4),(1080,8)) if mask&bit]
  if not qualities:return {"selected":urllib.parse.urlsplit(p).path,"error":"no_published_quality"}
  quality=min(qualities)
  media="https://xiaoshenke.net/vid/"+a.group(1)[::-1]+"/"+str(quality)
  with urllib.request.urlopen(req(media,player,"bytes=0-255"),timeout=25) as r:
   assert r.status==206 and r.headers.get("Content-Type","").split(";")[0].lower()=="video/mp4"
   c_range=r.headers.get("Content-Range","")
   m=re.fullmatch(r"bytes 0-255/(\d+)",c_range)
   assert m and r.read(256)[4:8]==b"ftyp"
   declared=int(m.group(1))
  return {"selected":urllib.parse.urlsplit(p).path,"selected_id":a.group(1),
    "quality":quality,"bytes":declared,"referer":player,"source":media}
 except Exception as e:
  return {"selected":urllib.parse.urlsplit(p).path,"error":type(e).__name__}

def main():
 page=site(PUBLISHER)
 html_links=Listings();html_links.feed(page)
 articles=[]
 for raw in html_links.pages:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(PUBLISHER,html.unescape(raw)))
  if (u.scheme=="https" and u.hostname in ("fullporner.com","www.fullporner.com")
    and re.fullmatch(r"/watch/[0-9a-fA-F]{24}/?",u.path)):
   link=u.geturl()
   if link not in articles:articles.append(link)
  if len(articles)>=12:break
 assert articles,("Official homepage has no published selected watch pages")
 print(json.dumps({"stage":"PUBLIC_PUBLISHER_SELECTION",
  "selected_watch_pages":len(articles)}),flush=True)
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
  results=list(pool.map(inspect,articles))
 valid=sorted((x for x in results if "bytes" in x),
  key=lambda row:row["bytes"])
 print(json.dumps({"stage":"ACTUAL_SELECTED_PUBLIC_SIZES",
  "valid_selected_count":len(valid),
  "smallest":[{"id":v["selected_id"],"quality":v["quality"],
    "bytes":v["bytes"]} for v in valid[:6]],
  "rejected":len(results)-len(valid)}),flush=True)
 permitted=[v for v in valid if 1_000_000<v["bytes"]<120_000_000]
 if not permitted:
  print(json.dumps({"provider":"fullporner","status":"NO_SMALL_AUTHENTIC_SELECTED_ORIGINAL",
    "min_verified_original_bytes":valid[0]["bytes"] if valid else None}),flush=True)
  return 2
 chosen=permitted[0]
 case={"provider":"fullporner","selected_id":chosen["selected_id"],
  "quality":chosen["quality"],"source":chosen["source"],
  "referer":chosen["referer"]}
 return full_verify(case,330)
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"fullporner","status":"FAILED_NOT_COMPLETE",
    "type":type(e).__name__,"reason":str(e)[:180]}),flush=True)
  sys.exit(3)
