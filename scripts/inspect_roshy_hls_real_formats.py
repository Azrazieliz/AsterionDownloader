#!/usr/bin/env python3
"""Validate exact selected Roshy HLS manifest and playable format metadata.

No full file and no signed URLs displayed. Uses exact publisher selected
Kamehaus page, its PACKER text and official manifest, not a guessed embed.
"""
import json,re,sys,urllib.parse
from pathlib import Path
import verify_roshy_selected_packed_hls as p
def main():
 with p.request(p.URL,p.PARENT) as res:
  assert res.status==200
  body=res.read(220000).decode("utf-8","replace")
 q=p.Script();q.feed(body)
 packed=[p.deobfuscate(raw) for raw in q.raw if
  "eval(function(p,a,c,k,e,d)" in raw]
 assert len(packed)==1 and packed[0]
 text=packed[0]
 assert "tt242urr647yem" in text and "jwplayer" in text
 start=re.search(r"\bvar\s+links\s*=\s*",text)
 assert start
 links,_=json.JSONDecoder().raw_decode(text[start.end():].lstrip())
 source=urllib.parse.urljoin(p.URL,links["hls4"])
 uri=urllib.parse.urlsplit(source)
 assert uri.scheme=="https" and uri.hostname=="kamehaus.net"
 assert re.fullmatch(r"/stream/[A-Za-z0-9_/-]{10,250}/master\.m3u8",uri.path)
 with p.request(source,p.URL) as res:
  playlist=res.read(200000).decode("utf-8","replace")
  assert res.status==200 and playlist.lstrip().startswith("#EXTM3U")
  variant=playlist.count("#EXT-X-STREAM-INF")
  encryption=playlist.count("#EXT-X-KEY")
 print(json.dumps({"provider":"roshy","stage":"SELECTED_HLS_PLAYLIST",
  "http":200,"variant_count":variant,"key_tags":encryption,
  "host":uri.hostname,"playlist_bytes":len(playlist)}),flush=True)
 wheel=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
 sys.path.insert(0,str(wheel))
 import yt_dlp
 assert yt_dlp.version.__version__=="2026.10.26"
 with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,"skip_download":True,
     "noplaylist":True,"socket_timeout":20,"retries":0,
     "http_headers":{"User-Agent":p.UA,"Referer":p.URL}}) as y:
  meta=y.extract_info(source,download=False)
 formats=[x for x in meta.get("formats",[]) if x.get("url") and x.get("vcodec")!="none"]
 safe=[{"height":f.get("height"),"format_id":f.get("format_id"),
        "ext":f.get("ext"),"protocol":f.get("protocol"),
        "tbr":f.get("tbr")} for f in formats]
 print(json.dumps({"provider":"roshy","status":
    "SELECTED_HLS_METADATA_VERIFIED_NOT_COMPLETE" if safe else
    "HLS_METADATA_UNSUPPORTED",
    "duration_seconds":meta.get("duration"),
    "format_count":len(safe),"formats":safe[:12]}),flush=True)
 return 0 if safe else 2
if __name__=="__main__":
 try:sys.exit(main())
 except Exception as ex:
  print(json.dumps({"status":"FAILED_NOT_COMPLETE","type":type(ex).__name__,
    "error":str(ex)[:200]}),flush=True);sys.exit(3)
