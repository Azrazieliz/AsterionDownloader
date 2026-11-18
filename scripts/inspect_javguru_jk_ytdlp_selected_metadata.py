#!/usr/bin/env python3
"""Compare production-bundled yt-dlp against authorized selected JK HLS read.

Selected owner post and exact published Maxstream JK signed 720p variant only.
No media downloads, no tokens, URLs, cookies or signed paths printed.
"""
import json,sys,re,urllib.parse,urllib.request
from pathlib import Path
from verify_javguru_owner_jk_full_pc import selected_final,WHEEL
from inspect_javguru_jk_hls_finality import UA
def main():
    embed,master,media,stats=selected_final()
    if not WHEEL.is_file():raise RuntimeError("production engine wheel missing")
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    print(json.dumps({"stage":"EXACT_JK_PLAYER_AND_BUNDLED_ENGINE",
      "version":yt_dlp.version.__version__,"media_variant_final":stats["endlist"],
      "selected_duration_s":stats["runtime_s"]}),flush=True)
    for name,ref in [("publisher_embed",embed),("selected_master",master),("selected_variant",media)]:
        print(json.dumps({"stage":"YTDLP_SELECTED_REFERER_TRIAL","referer_type":name}),flush=True)
        opts={"quiet":True,"no_warnings":True,"noplaylist":True,
          "skip_download":True,"format":"best",
          "retries":0,"fragment_retries":0,"socket_timeout":18,
          "http_headers":{"User-Agent":UA,"Referer":ref,
              "Accept-Encoding":"identity"}}
        try:
            with yt_dlp.YoutubeDL(opts) as client:
                info=client.extract_info(media,download=False)
            formats=info.get("formats",[]) if isinstance(info,dict) else []
            print(json.dumps({"referer_type":name,"status":"SELECTED_MEDIA_METADATA_OK",
              "extractor":info.get("extractor") if isinstance(info,dict) else None,
              "format_count":len(formats),"source_is_exact_selected":True}),flush=True)
        except Exception as e:
            msg=str(e)
            code=re.search(r"HTTP Error ([0-9]{3})",msg)
            print(json.dumps({"referer_type":name,
              "status":"SELECTED_MEDIA_EXTRACTOR_FAILED",
              "error_type":type(e).__name__,
              "http_error":int(code.group(1)) if code else None}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"status":"SELECTED_JK_METADATA_NOT_VERIFIED",
           "error_class":type(e).__name__}),flush=True)
        sys.exit(3)
