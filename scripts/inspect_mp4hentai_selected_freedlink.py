#!/usr/bin/env python3
"""Owner-selected MP4Hentai -> Mirrored.to official /getlink -> Freedl.ink.

The file identifier must be obtained from an actual first-party selected
publisher link; never guessed or enumerated. Follow no redirects off approved
hosters, do not solve CAPTCHA and do not treat HTML as a verified original.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from inspect_mp4hentai_mirror_normal_click import main as selected
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,url):return None
def main():
 sources=selected(return_selected_freedlink=True)
 if not isinstance(sources,list) or len(sources)!=1:
  raise RuntimeError("No unique publisher-selected Freedl file")
 url=sources[0];v=urllib.parse.urlsplit(url)
 if v.scheme!="https" or v.hostname not in ("freedl.ink","www.freedl.ink") or not re.fullmatch(r"/[A-Za-z0-9_-]{6,80}/?",v.path):
  raise RuntimeError("Not same official selected file")
 request=urllib.request.Request(url,headers={
  "User-Agent":UA,
  "Referer":"https://www.mirrored.to/files/DF7298C6/?dl=0",
  "Accept-Encoding":"identity","Range":"bytes=0-4095"})
 try:
  with urllib.request.build_opener(NoRedirect).open(request,timeout=24) as r:
   first=r.read(240000);mime=r.headers.get("Content-Type","").split(";")[0]
   text=first.decode("utf8","replace") if "html" in mime else ""
   print(json.dumps({"provider":"mp4hentai","stage":"PUBLISHER_SELECTED_FREEDLINK",
    "official_selected_source":True,
    "http":r.status,"mime":mime,
    "response_bytes":len(first),
    "mp4_magic":first[4:8]==b"ftyp",
    "m3u8_magic":first.lstrip().startswith(b"#EXTM3U"),
    "has_direct_download_control":bool(re.search(
      r">[ \t]*(?:direct )?download(?: file)?[ \t]*<|download-button|download_link",text,re.I)),
    "has_captcha":bool(re.search(r"captcha|turnstile|recaptcha",text,re.I)),
    "requires_login":bool(re.search(r"please login|sign in to download|premium only",text,re.I)),
    "complete_original_verified":False}),flush=True)
 except urllib.error.HTTPError as e:
  u=urllib.parse.urlsplit(e.headers.get("Location") or "")
  print(json.dumps({"provider":"mp4hentai","stage":"PUBLISHER_SELECTED_FREEDLINK",
   "official_selected_source":True,"http":e.code,
   "mime":e.headers.get("Content-Type","").split(";")[0],
   "redirect_host":u.hostname,"redirect_has_query":bool(u.query),
   "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
   "status":"SELECTED_FREEDLINK_NOT_VERIFIED",
   "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
