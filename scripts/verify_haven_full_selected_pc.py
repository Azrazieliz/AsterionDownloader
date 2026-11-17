#!/usr/bin/env python3
"""Whole-file PC acceptance for ONE publisher-attested HentaiHaven episode.

Uses the original site's selected series -> episode route -> that episode's own
JSON-LD VideoObject HLS URL, without picking recommendation/preview media.
Runs the APK-bundled yt-dlp in a temporary PC directory, checks every saved
byte with ffprobe and SHA256, and removes the temporary media on exit.
This never builds an APK, changes an Android queue, or proves phone acceptance.
"""
import hashlib, html, json, re, shutil, subprocess, sys, tempfile, urllib.parse, urllib.request
from html.parser import HTMLParser
from pathlib import Path

SERIES="https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
WHEEL=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
class Items(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]
        self.scripts=[]
        self.active=None
    def handle_starttag(self,t,a):
        d=dict(a)
        if t=="a" and d.get("href"):self.links.append(d["href"])
        if t=="script" and d.get("type","").lower()=="application/ld+json":self.active=""
    def handle_data(self,s):
        if self.active is not None and len(self.active)<400000:self.active+=s
    def handle_endtag(self,t):
        if t=="script" and self.active is not None:
            self.scripts.append(self.active)
            self.active=None

def get(url,referer):
    r=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(r,timeout=25) as response:
        if response.status!=200:raise RuntimeError("Selected publisher page not HTTP 200")
        return response.read(1_000_000).decode("utf8","replace")

def objects(data):
    if isinstance(data,list):
        for x in data:yield from objects(x)
    elif isinstance(data,dict):
        kind=data.get("@type")
        if kind=="VideoObject" or (isinstance(kind,list) and "VideoObject" in kind):
            yield data
        for x in data.values():
            if isinstance(x,(dict,list)):yield from objects(x)

def selected_original():
    series=Items();series.feed(get(SERIES,SERIES))
    path=urllib.parse.urlsplit(SERIES).path.rstrip("/")
    exact=set()
    for href in series.links:
        url=urllib.parse.urljoin(SERIES,html.unescape(href))
        u=urllib.parse.urlsplit(url)
        if(u.scheme=="https" and u.hostname=="hentaihaven.xxx" and not u.query
            and re.fullmatch(re.escape(path)+r"/episode-[0-9]{1,5}/?",u.path)):
            exact.add(url)
    if not exact:raise RuntimeError("Selected series published no episodes")
    episode=sorted(exact)[0]
    doc=Items();doc.feed(get(episode,SERIES))
    manifests=set()
    for raw in doc.scripts:
        try:record=json.loads(html.unescape(raw))
        except (ValueError,TypeError):continue
        for obj in objects(record):
            declared=obj.get("url","")
            if declared and declared.rstrip("/")!=episode.rstrip("/"):continue
            link=obj.get("contentUrl","")
            if not isinstance(link,str):continue
            u=urllib.parse.urlsplit(link)
            if(u.scheme=="https" and u.hostname=="octopusmanifest.org"
                and u.path.endswith(".m3u8") and not u.query
                and not u.username and not u.password and not u.fragment):
                manifests.add(link)
    if len(manifests)!=1:raise RuntimeError("No unique selected-episode publisher HLS")
    return episode,manifests.pop(),len(exact)

def main():
    if not WHEEL.is_file():raise RuntimeError("Exact APK extractor wheel missing")
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    if yt_dlp.version.__version__!="2026.10.26":
        raise RuntimeError("Wrong embedded extractor version")
    episode,hls,count=selected_original()
    print(json.dumps({"provider":"hentaihaven","stage":"EXACT_EPISODE_SELECTED",
        "publisher_episodes":count,"selected_episode_path":
        urllib.parse.urlsplit(episode).path.rsplit("/",2)[-2:],
        "source_host":"octopusmanifest.org"}),flush=True)
    with tempfile.TemporaryDirectory(prefix="asterion_haven_pc_complete_") as directory:
        destination=Path(directory)
        opts={"quiet":True,"no_warnings":True,
            "noplaylist":True,"outtmpl":str(destination/"selected-original.%(ext)s"),
            "format":"best","ignore_no_formats_error":True,
            "http_headers":{"User-Agent":UA,"Referer":episode},
            "socket_timeout":22,"retries":1,"fragment_retries":1,
            "skip_unavailable_fragments":False,"concurrent_fragment_downloads":2,
            "nopart":False,"overwrites":False}
        with yt_dlp.YoutubeDL(opts) as downloader:
            metadata=downloader.extract_info(hls,download=False)
            available=metadata.get("formats",[]) if isinstance(metadata,dict) else []
            print(json.dumps({"provider":"hentaihaven","stage":"ACTUAL_HLS_FORMATS",
                "result_type":metadata.get("_type") if isinstance(metadata,dict) else None,
                "format_count":len(available),
                "entries_count":len(metadata.get("entries") or [])
                    if isinstance(metadata,dict) else 0,
                "variants":[{"id":f.get("format_id"),"height":f.get("height"),
                    "protocol":f.get("protocol"),"ext":f.get("ext")}
                    for f in available[:8] if isinstance(f,dict)]}),flush=True)
            if not available:
                raise RuntimeError("Selected episode HLS exposes no downloadable formats")
            videos=sorted((f for f in available if isinstance(f,dict) and
                isinstance(f.get("height"),int) and f["height"]>=240 and
                f.get("protocol")=="m3u8_native"),
                key=lambda f:f["height"])
            audios=[f for f in available if isinstance(f,dict) and
                str(f.get("format_id","")).startswith("audio-") and
                f.get("protocol")=="m3u8_native"]
            if not videos or len(audios)!=1:
                raise RuntimeError("Cannot select one video and its published audio track")
            chosen=next((f for f in videos if f["height"]<=480),videos[0])
            format_id=str(chosen["format_id"])+"+"+str(audios[0]["format_id"])
            print(json.dumps({"provider":"hentaihaven","stage":"SELECTED_HLS_RENDITION",
                "video_height":chosen["height"],
                "audio_track_count":len(audios),
                "format_id_is_publisher_listed":True}),flush=True)
        with yt_dlp.YoutubeDL({**opts,"format":format_id,
                "ignore_no_formats_error":False}) as transfer:
            transfer.extract_info(hls,download=True)
        files=[f for f in destination.iterdir()
            if f.is_file() and f.suffix.lower() in (".mp4",".mkv",".ts",".webm")]
        if len(files)!=1:raise RuntimeError("Selected original did not yield exactly one final media")
        movie=files[0]
        length=movie.stat().st_size
        if length<1_000_000:raise RuntimeError("Final media file too small")
        probe=shutil.which("ffprobe")
        if not probe:raise RuntimeError("ffprobe unavailable for whole-file verification")
        process=subprocess.run([probe,"-v","error",
            "-show_entries","format=duration,format_name:stream=codec_type",
            "-of","json",str(movie)],capture_output=True,text=True,timeout=35)
        if process.returncode!=0:raise RuntimeError("Downloaded file failed ffprobe")
        receipt=json.loads(process.stdout)
        duration=float(receipt.get("format",{}).get("duration") or 0)
        tracks=[s.get("codec_type") for s in receipt.get("streams",[])]
        if duration<60 or "video" not in tracks:
            raise RuntimeError("Downloaded media not verified as full playable video")
        sha=hashlib.sha256()
        with movie.open("rb") as stream:
            while True:
                chunk=stream.read(1024*1024)
                if not chunk:break
                sha.update(chunk)
        print(json.dumps({"provider":"hentaihaven",
            "status":"COMPLETE_SELECTED_PC_FILE",
            "bytes":length,"duration_seconds":round(duration,3),
            "sha256_prefix":sha.hexdigest()[:24],
            "video":True,"audio":"audio" in tracks,
            "android_receipt_verified":False,
            "temp_media_retained":False}),flush=True)
    return 0

if __name__=="__main__":
    try:raise SystemExit(main())
    except Exception as error:
        print(json.dumps({"provider":"hentaihaven","status":"NOT_COMPLETE",
            "error_type":type(error).__name__,
            "message":re.sub(r"https?://[^\\s]+","[redacted]",str(error))[:180],
            "android_receipt_verified":False}),flush=True)
        raise SystemExit(3)
