#!/usr/bin/env python3
"""Exact selected MP4Hentai file via BuzzHeavier's DOCUMENTED public /d/ route.

Only ID is taken from owner-clicked Mirrored.to selected file host link.
BuzzHeavier's public custom landing-page documentation describes /d/{fileId};
this does not guess or enumerate IDs, bypass verification, or fetch unrelated
media. HEAD and a <=4096-byte Range test, no redirects followed.
"""
import json,re,sys,urllib.parse,urllib.request,urllib.error
from inspect_mp4hentai_mirror_normal_click import main as selected_mirror
from inspect_mp4hentai_selected_buzzheavier import UA
DOC_ROUTE="https://buzzheavier.com/d/"
class DenyRedirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,url):return None
def main():
 links=selected_mirror(return_selected_buzzheavier=True)
 if not isinstance(links,list) or len(links)!=1:
  raise RuntimeError("No unique exact publisher-approved BuzzHeavier file")
 share=links[0]
 url=urllib.parse.urlsplit(share)
 if(url.scheme!="https" or url.hostname not in ("buzzheavier.com","www.buzzheavier.com") or
    url.query or url.fragment or not re.fullmatch(r"/[A-Za-z0-9_-]{6,100}/?",url.path)):
  raise RuntimeError("Selected BuzzHeavier file ID unverified")
 item=url.path.strip("/")
 direct=DOC_ROUTE+item
 opener=urllib.request.build_opener(DenyRedirect)
 for referer in (share,"https://www.mirrored.to/files/DF7298C6/?dl=0"):
  headers={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity","Range":"bytes=0-4095"}
  req=urllib.request.Request(direct,headers=headers)
  try:
   with opener.open(req,timeout=22) as response:
    data=response.read(4096)
    mime=response.headers.get("Content-Type","").split(";")[0]
    print(json.dumps({"provider":"mp4hentai",
      "stage":"OFFICIAL_SELECTED_BUZZHEAVIER_DOCUMENTED_DIRECT",
      "selected_file_id_from_official_mirror":True,
      "http":response.status,"mime":mime,
      "size_header":int(response.headers.get("Content-Length") or 0),
      "content_range_present":bool(response.headers.get("Content-Range")),
      "first_bytes":len(data),"mp4_header":data[4:8]==b"ftyp",
      "zip_header":data[:4]==b"PK\x03\x04",
      "referer_origin":urllib.parse.urlsplit(referer).hostname,
      "complete_original_verified":False}),flush=True)
  except urllib.error.HTTPError as ex:
   dest=urllib.parse.urlsplit(ex.headers.get("Location") or "")
   print(json.dumps({"provider":"mp4hentai",
      "stage":"OFFICIAL_SELECTED_BUZZHEAVIER_DOCUMENTED_DIRECT",
      "selected_file_id_from_official_mirror":True,
      "http":ex.code,"mime":ex.headers.get("Content-Type","").split(";")[0],
      "redirect_host":dest.hostname,"redirect_has_token":bool(dest.query),
      "referer_origin":urllib.parse.urlsplit(referer).hostname,
      "complete_original_verified":False}),flush=True)
  except Exception as ex:
   print(json.dumps({"provider":"mp4hentai",
      "stage":"OFFICIAL_SELECTED_BUZZHEAVIER_DOCUMENTED_DIRECT",
      "error_type":type(ex).__name__,
      "referer_origin":urllib.parse.urlsplit(referer).hostname,
      "complete_original_verified":False}),flush=True)
 return 0
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as e:
  print(json.dumps({"provider":"mp4hentai",
    "status":"SELECTED_OFFICIAL_BUZZHEAVIER_DIRECT_UNVERIFIED",
    "error_type":type(e).__name__}),flush=True)
  sys.exit(4)
