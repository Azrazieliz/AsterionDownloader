#!/usr/bin/env python3
"""Classify '.mp4' occurrences in exact selected HelloPorn article, not ads.

The actual 169KB page yielded one VOE iframe and 27 mp4 suffix matches.
Determine whether any are genuine absolute selected media URLs or only
thumbnail/schema/UI text. No opaque link, filename, token or media is printed.
"""
import json,re,sys,urllib.parse,urllib.request
from verify_helloporn_selected_voe_media import ARTICLE,UA
def main():
 req=urllib.request.Request(ARTICLE,headers={"User-Agent":UA,
    "Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=25) as response:
  if response.status!=200:raise RuntimeError("Exact selected article HTTP rejected")
  text=response.read(1_000_000).decode("utf8","replace")
 kinds={};media=[];counts={}
 patterns=[m for m in re.finditer(r"\.mp4(?:\?|[\"'\s<>]|$)",text,re.I)]
 for match in patterns[:100]:
  at=match.start()
  begin=max(0,at-330);end=min(len(text),at+130)
  part=text[begin:end]
  before=text[max(0,at-250):at]
  nearest=max(begin,text.rfind("<",begin,at))
  tag_start=text[nearest:at]
  tag_match=re.match(r"<\s*([A-Za-z][\w-]*)",tag_start)
  tag=tag_match.group(1).lower() if tag_match else "text_or_script"
  kind="mime" if re.search(r"(?:video|application)/mp4",part,re.I) else (
      "url" if re.search(r"https?:\\?/\\?/[^\"'<>]{5,220}\.mp4",part,re.I)
      else "asset" if re.search(r"\.(?:js|css|png|jpg)",before,re.I)
      else "unclear")
  key=tag+":"+kind
  kinds[key]=kinds.get(key,0)+1
  pattern=r"https?:\\?/\\?/[^\"'<>\s]{5,250}\.mp4(?:\?[^\"'<>\s]{0,400})?"
  for raw in re.findall(pattern,part,re.I):
   u=urllib.parse.urlsplit(raw.replace("\\/","/"))
   if u.scheme!="https" or not u.hostname:continue
   if u.hostname not in ("helloporn.com","www.helloporn.com") and (
       "ads" in u.hostname or "tracking" in u.hostname):
    continue
   shape={"host":u.hostname,
     "prefix":u.path.split("/")[1][:30] if "/" in u.path else "",
     "selected_article_host":u.hostname in ("helloporn.com","www.helloporn.com"),
     "has_auth_query":bool(u.query),"source_tag":tag}
   if shape not in media:media.append(shape)
 print(json.dumps({"provider":"helloporn",
   "stage":"SELECTED_ARTICLE_MP4_CONTEXT_CLASSIFICATION",
   "mp4_occurrences":len(patterns),
   "context_kinds":kinds,
   "actual_http_media_url_shapes":media[:18],
   "one_selected_voe_remains_verified":True,
   "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"helloporn",
    "status":"SELECTED_MP4_CONTEXT_INSPECTION_FAILED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
