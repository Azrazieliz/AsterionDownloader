#!/usr/bin/env python3
"""Follow only the real MP4Hentai selected Mirror -> Freedl -> frdl redirect.

File ID originates from the exact owner-selected MP4Hentai Mirrored.to
/file/DF7298C6 official clicked download links, never guesses, no ads.
Inspect first-party Freedl.link HTTP redirects and HTML download controls;
do not automatically follow or download unknown off-site content. Print no
signed URLs, cookies, file IDs, or challenge tokens. Not acceptance by itself.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error,http.cookiejar
from html.parser import HTMLParser
from inspect_mp4hentai_mirror_normal_click import main as selected
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
# Official selected Freedl.ink now HTTP 302 -> frdl.io -> HTTP 301 frdl.hk.
# These are actual observed redirect hops, not guessed file mirrors.
ALLOWED={"freedl.ink","www.freedl.ink","frdl.io","www.frdl.io",
         "frdl.hk","www.frdl.hk"}
REFERER="https://www.mirrored.to/files/DF7298C6/?dl=0"
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,url):return None
class DownloadAnchors(HTMLParser):
 def __init__(self):super().__init__();self.current=None;self.links=[];self.forms=0
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag=="a" and a.get("href"):
   self.current={"href":a["href"],"text":"","class":a.get("class",""),"id":a.get("id","")}
   self.links.append(self.current)
  if tag=="form":self.forms+=1
 def handle_data(self,s):
  if self.current and len(self.current["text"])<120:self.current["text"]+=s
 def handle_endtag(self,tag):
  if tag=="a":self.current=None
def main(return_selected_terminal=False):
 items=selected(return_selected_freedlink=True)
 if not isinstance(items,list) or len(items)!=1:
  raise RuntimeError("Exact owner-selected file host not unique")
 current=items[0];seen=set();cookies=http.cookiejar.CookieJar()
 opener=urllib.request.build_opener(NoRedirect,
      urllib.request.HTTPCookieProcessor(cookies))
 referer=REFERER
 for hop in range(5):
  p=urllib.parse.urlsplit(current)
  if(p.scheme!="https" or p.hostname not in ALLOWED or p.username or p.password
     or p.fragment or p.port or current in seen):
   raise RuntimeError("Untrusted or repeated selected file redirect")
  seen.add(current)
  req=urllib.request.Request(current,headers={
    "User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity",
    "Range":"bytes=0-4095"})
  code=None;headers=None;data=b""
  try:
   with opener.open(req,timeout=25) as r:
    code=r.status;headers=r.headers;data=r.read(250000)
  except urllib.error.HTTPError as e:
   code=e.code;headers=e.headers
   if code not in (301,302,303,307,308):
    data=e.read(250000)
  mime=(headers.get("Content-Type") or "").split(";")[0].lower()
  new=headers.get("Location") or ""
  dest=urllib.parse.urlsplit(urllib.parse.urljoin(current,new))
  if code in (301,302,303,307,308):
   print(json.dumps({"provider":"mp4hentai",
     "stage":"EXACT_FREEDL_SELECTED_REDIRECT",
     "hop":hop,"host":p.hostname,"http":code,
     "destination_host":dest.hostname,
     "destination_scheme":dest.scheme,
     "location_has_query":bool(dest.query),
     "trusted_firstparty_transition":dest.hostname in ALLOWED,
     "same_file_path":dest.path==p.path,
     "source_path_segments":len([x for x in p.path.split("/") if x]),
     "destination_path_segments":len([x for x in dest.path.split("/") if x]),
     "complete_original_verified":False}),flush=True)
   if(dest.scheme!="https" or dest.hostname not in ALLOWED or
     dest.port or dest.username or dest.password or dest.fragment):
    return 3
   referer=current;current=dest.geturl();continue
  doc=DownloadAnchors();content=data.decode("utf8","replace") if "html" in mime else ""
  if content:doc.feed(content)
  candidates=[]
  for item in doc.links[:180]:
   label=(item["text"]+" "+item["class"]+" "+item["id"]).strip()
   if not re.search(r"download|get file|free",label,re.I):continue
   u=urllib.parse.urlsplit(urllib.parse.urljoin(current,html.unescape(item["href"])))
   if u.scheme=="https" and u.hostname and not u.username and not u.password:
    candidates.append({"host":u.hostname,
      "same_host":u.hostname==p.hostname,
      "download_label":label[:45],
      "route_prefix":u.path.split("/")[1][:30] if "/" in u.path else "",
      "url_has_token":bool(u.query)})
  print(json.dumps({"provider":"mp4hentai",
     "stage":"EXACT_SELECTED_FREEDL_TERMINAL",
     "hop":hop,"host":p.hostname,"http":code,"mime":mime,
     "sample_bytes":len(data),
     "range_header":bool(headers.get("Content-Range")),
     "mp4_signature":data[4:8]==b"ftyp",
     "hls_signature":data.lstrip().startswith(b"#EXTM3U"),
     "named_download_links":candidates[:10],
     "form_count":doc.forms,
     "visible_captcha":bool(re.search(r"captcha|turnstile|verify you are human",
       content,re.I)),
     "complete_original_verified":False}),flush=True)
  if return_selected_terminal:
   return {"selected_url":current,"referer":referer,"mime":mime,
           "http":code,"html":content,"cookies":cookies}
  return 0 if code in (200,206) else 4
 return 5
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
    "status":"SELECTED_FREEDL_REDIRECT_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(6)
