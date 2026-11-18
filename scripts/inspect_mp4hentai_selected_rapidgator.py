#!/usr/bin/env python3
"""Inspect only selected MP4Hentai official Mirrored.to -> Rapidgator file.

Uses the actual publisher's clicked Mirror /getlink and only the exact
published Rapidgator /file route. Does not guess IDs, automate paid tiers,
bypass CAPTCHA, follow unrelated redirects, or download a placeholder.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from inspect_mp4hentai_mirror_normal_click import main as selected_mirror
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class NoRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,url):return None
def main():
 links=selected_mirror(return_selected_rapidgator=True)
 if len(links)!=1:raise RuntimeError("Selected Rapidgator route not unique")
 target=links[0]
 p=urllib.parse.urlsplit(target)
 if(p.scheme!="https" or p.hostname not in ("rapidgator.net","www.rapidgator.net") or
    not p.path.startswith("/file/") or p.username or p.password):
  raise RuntimeError("Publisher-selected file host route is invalid")
 request=urllib.request.Request(target,headers={
  "User-Agent":UA,"Referer":"https://www.mirrored.to/files/DF7298C6/?dl=0",
  "Accept-Encoding":"identity","Range":"bytes=0-4095"})
 try:
  with urllib.request.build_opener(NoRedirect).open(request,timeout=25) as reply:
   first=reply.read(500_000)
   mime=reply.headers.get("Content-Type","").lower().split(";")[0]
   html=first.decode("utf8","replace") if "html" in mime else ""
   print(json.dumps({"provider":"mp4hentai",
     "stage":"EXACT_SELECTED_RAPIDGATOR_PUBLIC_FILE",
     "source_from_official_clicked_mirror":True,
     "http":reply.status,"mime":mime,"sample_bytes":len(first),
     "video_mp4_header":first[4:8]==b"ftyp",
     "html_requires_login":bool(re.search(r"sign in|login|register|membership",html,re.I)),
     "html_has_free_button":bool(re.search(r"free download|download free|download as free",html,re.I)),
     "html_has_captcha":bool(re.search(r"captcha|turnstile|h-captcha",html,re.I)),
     "html_has_wait":bool(re.search(r"wait \d+|countdown|seconds remaining",html,re.I)),
     "complete_original_verified":False}),flush=True)
 except urllib.error.HTTPError as e:
  link=urllib.parse.urlsplit(e.headers.get("Location") or "")
  print(json.dumps({"provider":"mp4hentai",
    "stage":"EXACT_SELECTED_RAPIDGATOR_PUBLIC_FILE",
    "source_from_official_clicked_mirror":True,
    "http":e.code,"mime":e.headers.get("Content-Type","").split(";")[0],
    "redirect_host":link.hostname,
    "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
   "status":"SELECTED_RAPIDGATOR_NOT_VERIFIED",
   "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
