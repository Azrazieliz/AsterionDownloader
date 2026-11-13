#!/usr/bin/env python3
"""Selected, public provider metadata test, never a content download.

Exactly the APK's bundled yt-dlp version is used. No cookies, proxy, user
account, media URLs, player tokens or content bytes are persisted.
"""
import json
import re
import subprocess
import sys
import urllib.parse
import urllib.request
import urllib.error
from datetime import datetime,timezone

SITES = {
 "Rule34Video 4727221": "https://rule34video.com/video/4727221/",
 "Eporner Gachinco": "https://www.eporner.com/video-MSADu4C0K92/",
 "HentaiBros Deco": "https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
 "3DHentaiX NTR": "https://3dhentaix.com/ntr-compilation/",
 "FullPorner selected": "https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
 "FreePornVideos Serena": "https://www.freepornvideos.xxx/videos/93823084/serena-gomes-a-savage-debut-in-a-gangbang-with-double-anal/",
 "HentaiHaven selected": "https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
 "UncensoredHentai selected": "https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
 "JAVIDEO sampled release": "https://javideo.net/en/fc2ppv-4994603",
}
def classify(raw:str)->str:
    low=raw.lower()
    if re.search(r"\b(?:404|410)\b|video unavailable|not found",low):
        return "publisher_not_found"
    if re.search(r"\b(?:401|403)\b|forbidden|login|sign in|captcha|verify you are human",low):
        return "provider_access_rejected"
    if "no video formats" in low or "requested format is not available" in low:
        return "no_video_formats"
    if "unable to extract hash" in low or "unable to extract short video id" in low:
        return "extractor_player_signature_changed"
    if "unsupported url" in low or "no suitable extractor" in low:
        return "no_supported_extractor"
    if "timed out" in low or "timeout" in low or "connection reset" in low:
        return "network_timeout_or_reset"
    if "geo" in low or "region" in low:
        return "regional_access"
    if "drm" in low:
        return "drm_protected"
    return "unclassified_extractor_failure"
def examine(name,url):
    # Separate subprocess enforces a hard wall-clock bound for EACH site.
    args=[sys.executable,"-m","yt_dlp","--no-playlist","--skip-download",
      "--dump-single-json","--no-warnings","--retries","0",
      "--fragment-retries","0","--extractor-retries","0",
      "--socket-timeout","12","--user-agent",
      "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
      url]
    result={"url_host":url.split("/")[2],"metadata_only":True,
            "media_bytes_saved":0,"yt_dlp_version":"2026.10.26"}
    try:
        child=subprocess.run(args,capture_output=True,text=True,
                             timeout=35,check=False)
    except subprocess.TimeoutExpired:
        result["category"]="metadata_probe_timeout_35s"
        return result
    result["exit_code"]=child.returncode
    if child.returncode!=0:
        result["category"]=classify(child.stderr[-3500:]+"\n"+child.stdout[-600:])
        return result
    try:
        metadata=json.loads(child.stdout)
        formats=metadata.get("formats",[])
        if not isinstance(formats,list):
            formats=[]
        result.update(category="metadata_found",extractor=metadata.get("extractor_key",""),
            duration_s=metadata.get("duration"),
            formats_count=len(formats),
            format_protocols=sorted(set(str(f.get("protocol","")) for f in formats))[:6])
        if name=="FreePornVideos Serena":
            # Confirm actual delivery from the source's selected original
            # format. Transfer at most 32 KiB, discard immediately, never
            # retain signed CDN URLs or material in diagnostics artifacts.
            candidate=next((f for f in sorted(formats,
                         key=lambda f:int(f.get("height") or 0),reverse=True)
                         if f.get("protocol") in ("https","http") and
                         str(f.get("url","")).startswith("https://") and
                         not f.get("has_drm")),None)
            if candidate is None:
                result["media_range"]={"status":"no_verified_https_format"}
            else:
                href=candidate["url"]
                req=urllib.request.Request(href,headers={
                    "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)",
                    "Referer":url,
                    "Range":"bytes=0-32767"})
                try:
                    with urllib.request.urlopen(req,timeout=13) as response:
                        fragment=response.read(32768)
                        result["media_range"]={
                            "http":response.status,
                            "bytes":len(fragment),
                            "mime":response.headers.get("Content-Type",""),
                            "content_range":bool(response.headers.get("Content-Range")),
                            "mp4_magic":len(fragment)>=12 and fragment[4:8]==b"ftyp",
                            "source_host":urllib.parse.urlsplit(href).hostname,
                            "media_path_extension":urllib.parse.urlsplit(href).path.rsplit(".",1)[-1].lower()
                              if "." in urllib.parse.urlsplit(href).path.rsplit("/",1)[-1] else "",
                            "media_link_has_query":bool(urllib.parse.urlsplit(href).query),
                            "retained_media_bytes":0}
                except Exception as exc:
                    result["media_range"]={
                        "http":getattr(exc,"code",0),
                        "error":type(exc).__name__,
                        "retained_media_bytes":0}
    except Exception:
        result["category"]="output_not_valid_json"
    return result

out={}
for name,url in SITES.items():
    out[name]=examine(name,url)
    print(name+": "+out[name]["category"],flush=True)
out["environment"]={"tested_at_utc":datetime.now(timezone.utc).isoformat(),
    "note":"Public metadata only; no authorized sessions. Not a real full download receipt."}
with open("site-extractor-metadata.json","w",encoding="utf-8") as file:
    json.dump(out,file,indent=2)
