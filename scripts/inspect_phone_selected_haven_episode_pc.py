#!/usr/bin/env python3
"""Check EXACT Samsung HentaiHaven failed job #80's selected episode on PC.
No original is downloaded and no selected URL or opaque media token printed.
"""
import html,json,re,sys,urllib.parse
from inspect_samsung_videoflow_jobs import PACKAGE
from retest_selected_samsung_video import snapshot
from verify_haven_full_selected_pc import Items,get,objects,UA,WHEEL
def main():
 selected=snapshot(80)["url"]
 u=urllib.parse.urlsplit(selected)
 if u.scheme!="https" or u.hostname not in ("hentaihaven.xxx","www.hentaihaven.xxx"):
  raise RuntimeError("job 80 is not an approved HentaiHaven publisher URL")
 is_episode=bool(re.fullmatch(
  r"/watch/[a-z0-9][a-z0-9-]{3,220}/episode-[0-9]{1,5}/?",u.path,re.I))
 if not is_episode:
  print(json.dumps({"stage":"EXACT_ANDROID_SELECTED_EPISODE",
    "is_episode":False,"path_depth":len(u.path.strip("/").split("/")),
    "error":"Selected job is a series rather than an episode"}),flush=True)
  return 4
 doc=Items();doc.feed(get(selected,selected))
 originals=set()
 for raw in doc.scripts:
  try:obj=json.loads(html.unescape(raw))
  except Exception:continue
  for video in objects(obj):
   if video.get("url","").rstrip("/") not in ("",selected.rstrip("/")):continue
   link=video.get("contentUrl")
   if not isinstance(link,str):continue
   p=urllib.parse.urlsplit(link)
   if(p.scheme=="https" and p.hostname=="octopusmanifest.org"
       and p.path.endswith(".m3u8") and not p.query and not p.fragment):
    originals.add(link)
 if len(originals)!=1:
  print(json.dumps({"stage":"EXACT_ANDROID_SELECTED_EPISODE",
   "is_episode":True,"approved_hls_count":len(originals)}),flush=True)
  return 4
 sys.path.insert(0,str(WHEEL))
 import yt_dlp
 fmt=("bv[height<=480]+ba[language=ja]/"
      "bv[height<=480]+ba[language=jpn]/"
      "bv[height<=480]+ba/bv+ba")
 with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,
   "format":fmt,"noplaylist":True,
   "http_headers":{"User-Agent":UA,"Referer":selected}}) as client:
  info=client.extract_info(next(iter(originals)),download=False)
 chosen=info.get("requested_formats") or []
 all_formats=info.get("formats") or []
 print(json.dumps({"stage":"EXACT_ANDROID_JOB_80_PC_HLS_FORMAT",
  "selected_episode":True,"approved_publisher_hls":True,
  "format_count":len(all_formats),
  "format_ids":[str(f.get("format_id",""))[:25] for f in chosen],
  "video_heights":[f.get("height") for f in chosen],
  "original_media_downloaded":False,"android_media_verified":False}),flush=True)
 return 0 if len(chosen)==2 else 4
if __name__=="__main__":
 try:raise SystemExit(main())
 except Exception as e:
  print(json.dumps({"stage":"EXACT_ANDROID_JOB_80_PC_DIAG_UNVERIFIED",
   "error_class":type(e).__name__,"detail":re.sub(r"https?://[^\\s]+","[URL]",str(e))[:150]}),flush=True)
  raise SystemExit(3)
