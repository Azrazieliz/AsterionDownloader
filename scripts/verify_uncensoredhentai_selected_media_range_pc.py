#!/usr/bin/env python3
"""First real bytes from ONLY normal owner-selected NHPlayer AJAX videoReady.

Selected UncensoredHentai article -> one official NHPlayer iframe -> one
first-party player.php with published i,s,vid -> ordinary Edge JS AJAX 200 ->
window.videoReady; check one tiny CDN byte range. No challenge bypass, no
CDN guessing, no sharing signed media URL, no download-success claim.
"""
import json,sys,urllib.parse,urllib.request,urllib.error
from inspect_uncensored_nhplayer_selected_cdp_network import main as browser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
def prove(media,player,frame):
 p=urllib.parse.urlsplit(media)
 if p.scheme!="https" or p.hostname!="r2.1hanime.com":
  raise RuntimeError("Current JS output is not exact first-party selected CDN")
 req=urllib.request.Request(media,headers={"User-Agent":UA,
      "Referer":player,"Accept-Encoding":"identity","Range":"bytes=0-4095"})
 try:
  with urllib.request.urlopen(req,timeout=24) as resp:
   content=resp.read(4096)
   end=urllib.parse.urlsplit(resp.url)
   if end.scheme!="https" or end.hostname!="r2.1hanime.com":
    raise RuntimeError("CDN redirected outside selected source")
   mime=resp.headers.get("Content-Type","").split(";")[0].lower()
   declared=resp.headers.get("Content-Range","")
   print(json.dumps({"provider":"uncensoredhentai",
      "stage":"AJAX_SELECTED_CDN_FIRST_BYTES",
      "first_party_ajax_published":True,"selected_cdn":p.hostname,
      "route_extension":p.path.rsplit(".",1)[-1][:12] if "." in p.path else "",
      "query_keys":sorted(urllib.parse.parse_qs(p.query))[:8],
      "http":resp.status,"mime":mime,"range_header":bool(declared),
      "sample_bytes":len(content),
      "mp4_signature":content[4:8]==b"ftyp",
      "hls_signature":content.lstrip().startswith(b"#EXTM3U"),
      "complete_original_verified":False}),flush=True)
   return 0 if resp.status in (200,206) and (
      content[4:8]==b"ftyp" or content.lstrip().startswith(b"#EXTM3U")) else 4
 except urllib.error.HTTPError as e:
  print(json.dumps({"provider":"uncensoredhentai",
      "stage":"AJAX_SELECTED_CDN_ACCESS_REFUSED","http":e.code,
      "selected_cdn":p.hostname,
      "complete_original_verified":False}),flush=True)
  return 5
if __name__=="__main__":
 try:sys.exit(browser(verified_source_callback=prove))
 except Exception as e:
  print(json.dumps({"provider":"uncensoredhentai",
     "status":"SELECTED_CDN_SAMPLE_UNVERIFIED",
     "error_type":type(e).__name__}),flush=True)
  sys.exit(6)
