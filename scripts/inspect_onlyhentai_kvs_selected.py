#!/usr/bin/env python3
"""Selected OnlyHentai KVS player evidence, no media transfer or token output."""
import json,re,urllib.request,urllib.parse
from html.parser import HTMLParser
ROOT="https://onlyhentaistuff.com/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Reader(HTMLParser):
 def __init__(self):
  super().__init__();self.links=[];self.scripts=[];self.inside=False;self.script=""
 def handle_starttag(self,tag,attrs):
  attrs=dict(attrs)
  if tag=="a" and attrs.get("href"):self.links.append(attrs["href"])
  if tag=="script" and not attrs.get("src"):
   self.inside=True;self.script=""
 def handle_data(self,data):
  if self.inside and len(self.script)<200000:self.script+=data
 def handle_endtag(self,tag):
  if tag=="script" and self.inside:
   self.scripts.append(self.script);self.inside=False
def get(url):
 with urllib.request.urlopen(urllib.request.Request(url,headers={
   "User-Agent":UA,"Accept":"text/html,*/*;q=0.7"}),timeout=20) as r:
  return r.read(2000000).decode("utf8","replace")
def main():
 root=Reader();root.feed(get(ROOT))
 selected=[]
 for link in root.links:
  url=urllib.parse.urljoin(ROOT,link);p=urllib.parse.urlsplit(url)
  if p.scheme=="https" and p.hostname in ("www.onlyhentaistuff.com",
       "onlyhentaistuff.com") and re.fullmatch(r"/videos/[^/]+/[^/?#]+/?",p.path):
   if url not in selected:selected.append(url)
 print(json.dumps({"discovered_exact_selected":len(selected)}),flush=True)
 for url in selected[:2]:
  page=Reader();html=get(url);page.feed(html)
  active=[s for s in page.scripts if "kt_player" in s]
  sourceNames={}
  for script in active:
   for match in re.finditer(r"""(?i)['"]?(video_url|video_alt_url|video_url_text|
       video_id|video_duration|duration|video_url_token|postfix|video_sources|
       cdn_host|flashvars|video_formats|license_code|video_url_text|video_url_text|
       video_url_text|video_url|video_url_high|video_url_low)['"]?\s*[:=]""",
        script,re.X):
    key=match.group(1).lower();sourceNames[key]=sourceNames.get(key,0)+1
  for key in ("video_url","video_alt_url","video_url_text","video_url_high"):
   # Do not leak signed links, only classify literal value shape if present.
   for m in re.finditer(r"""(?i)\b"""+key+r"""['"]?\s*:\s*['"]([^'"]{0,500})['"]""",html):
    value=m.group(1);u=urllib.parse.urlsplit(value)
    sourceNames[key+"_shape"]={"absolute_https":value.startswith("https://"),
      "host":u.hostname if u.scheme=="https" else None,
      "path_media_ext":bool(re.search(r"\.(mp4|m3u8|mpd)(?:$|\?)",u.path,re.I)),
      "encoded":value.startswith(("function/","enc:","base64:"))}
  p=urllib.parse.urlsplit(url)
  parts=[x for x in p.path.split("/") if x]
  selected_id=parts[1] if len(parts)==3 else ""
  declared_ids=set(re.findall(r"""(?i)(?:['"]?video_id['"]?\\s*:\\s*|\\bvideo_id\\s*=\\s*)['"]?([0-9]{1,12})""",
      active[0])) if len(active)==1 else set()
  js=active[0] if len(active)==1 else ""
  script_scope=("global_var" if re.search(r"\\bvar\\s+flashvars\\s*=",js)
      else "window_member" if "window.flashvars" in js
      else "local_lexical" if re.search(r"\\b(?:let|const)\\s+flashvars\\s*=",js)
      else "unknown")
  print(json.dumps({"probe":"ONLYHENTAI_SELECTED_KVS",
    "selected_path_depth":len(parts),"second_path_segment_numeric":parts[1].isdigit(),
    "script_count":len(page.scripts),"kvs_script_count":len(active),
    "kvs_script_lengths":[len(x) for x in active[:4]],
    "player_keys":sourceNames,
    "selected_publisher_id_matches":declared_ids=={selected_id},
    "kvs_script_scope":script_scope,
    "selected_player_element_exists":bool(re.search(r"""id=['"]kt_player['"]""",html)),
    "publisher_original_verified":False}),flush=True)
if __name__=="__main__":main()
