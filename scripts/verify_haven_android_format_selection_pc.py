#!/usr/bin/env python3
"""Verify exact embedded-Android HentaiHaven HLS format policy against live selected episode.

Does not save files, build an APK, or attempt Android installation.
"""
import json,sys
from pathlib import Path
from verify_haven_full_selected_pc import selected_original,UA,WHEEL
def main():
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    assert yt_dlp.version.__version__=="2026.10.26"
    episode,manifest,count=selected_original()
    expression="bv[height<=480]+ba/bv+ba"
    with yt_dlp.YoutubeDL({"quiet":True,"no_warnings":True,
            "format":expression,"noplaylist":True,
            "http_headers":{"User-Agent":UA,"Referer":episode}}) as dl:
        info=dl.extract_info(manifest,download=False)
    chosen=info.get("requested_formats") or []
    names=[f.get("format_id") for f in chosen]
    heights=[f.get("height") for f in chosen]
    valid=(len(chosen)==2 and
        any(isinstance(x,int) and x>200 for x in heights) and
        any(str(x).startswith("audio-") for x in names))
    print(json.dumps({"provider":"hentaihaven",
        "stage":"ANDROID_SELECTOR_POLICY_PC_EVIDENCE",
        "publisher_episodes":count,"format_expression":expression,
        "selected_format_ids":names,"selected_heights":heights,
        "requires_mpegts":True,"valid_video_plus_audio":valid,
        "bytes_downloaded":0}),flush=True)
    return 0 if valid else 2
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as err:
        print(json.dumps({"status":"SELECTOR_NOT_VERIFIED","type":type(err).__name__,
            "reason":str(err)[:140]}),flush=True)
        sys.exit(3)
