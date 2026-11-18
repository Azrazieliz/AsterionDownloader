#!/usr/bin/env python3
"""Inspect only the owner's verified MP4Hentai Mirrored.to file page.

Source identity is the actual publisher-selected Mirror button, known to
resolve to /files/DF7298C6/?dl=0. Do not click advertisements, guess file IDs,
download content or expose signed redirect URL/query values.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
URL="https://www.mirrored.to/files/DF7298C6/?dl=0"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Nodes(HTMLParser):
 def __init__(self):
  super().__init__();self.anchors=[];self.forms=[];self.active=None
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):
   self.active={"href":a["href"],"text":"","class":a.get("class",""),"id":a.get("id","")}
   self.anchors.append(self.active)
  if tag=="form":
   self.forms.append({"method":a.get("method",""),"action":a.get("action","")})
 def handle_data(self,s):
  if self.active and len(self.active["text"])<90:self.active["text"]+=s
 def handle_endtag(self,tag):
  if tag=="a":self.active=None
def main():
 req=urllib.request.Request(URL,headers={"User-Agent":UA,"Accept-Encoding":"identity"})
 with urllib.request.urlopen(req,timeout=27) as response:
  code=response.status;final=response.url;body=response.read(1_500_000).decode("utf8","replace")
 doc=Nodes();doc.feed(body)
 selected=[]
 for a in doc.anchors:
  u=urllib.parse.urlsplit(urllib.parse.urljoin(URL,html.unescape(a["href"])))
  if u.hostname not in ("www.mirrored.to","mirrored.to"):continue
  if not u.path.startswith("/getlink"):continue
  qs=urllib.parse.parse_qs(u.query)
  selected.append({"route_depth":len([x for x in u.path.split("/") if x]),
    "query_keys":sorted(qs),"has_exact_hid_tid":set(qs)=={"hid","tid"},
    "anchor_id":a["id"],"text_kind":"download" if re.search(r"download|get link",a["text"],re.I) else "other"})
 print(json.dumps({"provider":"mp4hentai","stage":"EXACT_SELECTED_MIRROR_STATIC_PAGE",
   "http":code,"same_publisher_file":urllib.parse.urlsplit(final).path=="/files/DF7298C6/",
   "html_bytes":len(body),"getlink_anchor_count":len(selected),
   "getlink_controls":selected[:12],
   "requires_click":len(selected)==0 and (
    "download-link" in body or "getlink" in body),
   "captcha_present":bool(re.search(r"cf-turnstile|g-recaptcha|h-captcha|file-captcha",body,re.I)),
   "full_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai","status":"STATIC_SELECTED_MIRROR_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(3)
