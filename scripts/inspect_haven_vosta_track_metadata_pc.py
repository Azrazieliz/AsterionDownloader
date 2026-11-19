#!/usr/bin/env python3
"""Read-only exact publisher HentaiHaven episode track inventory for VOSTA.

VOSTA means original Japanese voices and English subtitles. A label such as
'audio-English' is NOT proof of English dubbing OR Japanese original audio.
Report declared tracks without downloading media or claiming language verified.
"""
import json, re, sys, urllib.request
from verify_haven_full_selected_pc import selected_original, UA, WHEEL

def attrs(ext_media):
    found = dict(re.findall(r'([A-Z][A-Z0-9-]*)=("[^"]*"|[^,]*)', ext_media))
    return {k: v.strip('"') for k,v in found.items()}

def main():
    episode, playlist, _ = selected_original()
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    assert yt_dlp.version.__version__ == "2026.10.26"
    with yt_dlp.YoutubeDL({
        "quiet":True, "no_warnings":True, "noplaylist":True,
        "http_headers":{"User-Agent":UA,"Referer":episode},
        "skip_download":True,
    }) as client:
        info = client.extract_info(playlist,download=False)
    variants=[]
    for f in info.get("formats",[])[:70]:
        variants.append({
            "id":str(f.get("format_id",""))[:90],
            "language":str(f.get("language",""))[:45],
            "video_ext":str(f.get("video_ext",""))[:18],
            "audio_ext":str(f.get("audio_ext",""))[:18],
            "vcodec":str(f.get("vcodec",""))[:22],
            "acodec":str(f.get("acodec",""))[:22],
            "height":f.get("height")
        })
    req=urllib.request.Request(playlist,headers={
        "User-Agent":UA,"Referer":episode,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(req,timeout=20) as resp:
        raw=resp.read(300_000).decode("utf-8","replace")
    entries=[]
    for line in raw.splitlines():
        if line.startswith("#EXT-X-MEDIA:"):
            record=attrs(line.split(":",1)[1])
            entries.append({key:record.get(key,"") for key in
                ("TYPE","GROUP-ID","NAME","LANGUAGE","DEFAULT",
                 "AUTOSELECT","FORCED","CHARACTERISTICS")})
    subs=info.get("subtitles") or {}
    automatic=info.get("automatic_captions") or {}
    print(json.dumps({
        "provider":"hentaihaven",
        "test":"VOSTA_METADATA_ONLY_NO_MEDIA_DOWNLOADED",
        "published_renditions":entries[:30],
        "extractor_formats":variants,
        "subtitle_languages":sorted(subs.keys())[:30],
        "automatic_caption_languages":sorted(automatic.keys())[:20],
        "has_explicit_japanese_audio":any(x.get("TYPE")=="AUDIO" and
            x.get("LANGUAGE","").lower() in ("ja","jpn","japanese")
            for x in entries),
        "has_explicit_english_subtitles":any(
            x.get("TYPE")=="SUBTITLES" and x.get("LANGUAGE","").lower()
            in ("en","eng","english") for x in entries),
        "voice_language_verified":False,
        "burned_english_subtitles_verified":False,
        "selected_episode_original_verified":False
    }),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as error:
        print(json.dumps({"status":"VOSTA_TRACKS_UNVERIFIED",
            "type":type(error).__name__,"reason":str(error)[:130]}),flush=True)
        raise SystemExit(2)
