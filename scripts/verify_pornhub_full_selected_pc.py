#!/usr/bin/env python3
"""Complete first-party selected Pornhub HLS PC acceptance using bundled engine.

Select only an actual /view_video.php?viewkey=<id> linked from the publisher's
public catalog, followed by the unique watch-page flashvars mediaDefinitions
on approved CDN hosts. Never guess original URLs or bypass geographic gates.
Staging is temporary. Does not touch Android, account or queue state.
"""
import hashlib,html,json,re,shutil,subprocess,sys,tempfile,urllib.parse,urllib.request
from html.parser import HTMLParser
from pathlib import Path
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
HOME="https://www.pornhub.com/"
WHEEL=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]
    def handle_starttag(self,t,a):
        d=dict(a)
        if t=="a" and d.get("href"):self.links.append(d["href"])

def get(url,ref):
    request=urllib.request.Request(url,headers={
        "User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(request,timeout=27) as response:
        if response.status!=200:raise RuntimeError("Selected first-party route not HTTP200")
        return response.read(2_000_000).decode("utf8","replace")

def sources():
    catalog=Links()
    catalog.feed(get(HOME,HOME))
    selected=[]
    for href in catalog.links:
        u=urllib.parse.urlsplit(urllib.parse.urljoin(HOME,html.unescape(href)))
        q=urllib.parse.parse_qs(u.query)
        if (u.scheme=="https" and u.hostname in ("pornhub.com","www.pornhub.com")
            and u.path=="/view_video.php" and
            len(q.get("viewkey",[]))==1 and
            re.fullmatch(r"[A-Za-z0-9_-]{5,50}",q["viewkey"][0])):
            if u.geturl() not in selected:selected.append(u.geturl())
    if not selected:raise RuntimeError("Publisher catalog has no selectable watch page")
    watch=selected[0]
    raw=get(watch,HOME)
    found=[]
    for match in re.finditer(r"\b(?:var\s+)?flashvars_[A-Za-z0-9_]{1,72}\s*=\s*",raw):
        try:obj,_=json.JSONDecoder().raw_decode(raw[match.end():].lstrip())
        except (ValueError,TypeError):continue
        if isinstance(obj,dict) and isinstance(obj.get("mediaDefinitions"),list):
            found.append(obj)
    if len(found)!=1:raise RuntimeError("Selected page has no unique publisher media configuration")
    options=[]
    for obj in found[0]["mediaDefinitions"]:
        if not isinstance(obj,dict) or obj.get("format") not in ("hls","hls_master"):continue
        link=obj.get("videoUrl","").replace("\\/","/")
        u=urllib.parse.urlsplit(link)
        if (u.scheme!="https" or u.hostname not in ("hm-h.phncdn.com","em-h.phncdn.com")
            or not u.path.endswith(".m3u8") or u.username or u.password):
            continue
        height=int(obj.get("quality") or 0)
        if 240<=height<=2160:options.append((height,link))
    if not options:raise RuntimeError("No first-party selected HLS")
    options.sort()
    return watch,options[0],len(options)

def main():
    assert WHEEL.is_file(),"bundled wheel unavailable"
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    assert yt_dlp.version.__version__=="2026.10.26"
    watch,(height,hls),count=sources()
    print(json.dumps({"provider":"pornhub","stage":"ONE_SELECTED_PUBLISHER_HLS",
        "published_qualities":count,"chosen_height":height,
        "android_receipt_verified":False}),flush=True)
    with tempfile.TemporaryDirectory(prefix="asterion_pornhub_pc_complete_") as folder:
        opts={"quiet":True,"no_warnings":True,
              "outtmpl":str(Path(folder)/"selected-original.%(ext)s"),
              "noplaylist":True,"format":"bv+ba/b",
              "hls_use_mpegts":True,"merge_output_format":"mkv",
              "http_headers":{"User-Agent":UA,"Referer":watch},
              "socket_timeout":25,"retries":1,"fragment_retries":1,
              "skip_unavailable_fragments":False,"concurrent_fragment_downloads":2}
        with yt_dlp.YoutubeDL(opts) as downloader:
            downloader.extract_info(hls,download=True)
        outputs=[x for x in Path(folder).iterdir() if x.is_file()
                 and x.suffix.lower() in (".mp4",".mkv",".ts",".webm")]
        if len(outputs)!=1:raise RuntimeError("Exactly one selected final file not produced")
        movie=outputs[0]
        length=movie.stat().st_size
        if length<1_000_000:raise RuntimeError("Incomplete small media file")
        ffprobe=shutil.which("ffprobe")
        if not ffprobe:raise RuntimeError("Missing FFprobe")
        result=subprocess.run([ffprobe,"-v","error","-show_entries",
            "format=duration:stream=codec_type","-of","json",str(movie)],
            capture_output=True,text=True,timeout=45)
        if result.returncode!=0:raise RuntimeError("Final media unplayable")
        payload=json.loads(result.stdout)
        duration=float(payload.get("format",{}).get("duration") or 0)
        tracks=[x.get("codec_type") for x in payload.get("streams",[])]
        if duration<60 or "video" not in tracks:
            raise RuntimeError("No complete playable selected original")
        hasher=hashlib.sha256()
        with movie.open("rb") as stream:
            while chunk:=stream.read(1024*1024):hasher.update(chunk)
        print(json.dumps({"provider":"pornhub","status":"COMPLETE_SELECTED_PC_FILE",
             "bytes":length,"duration_s":round(duration,3),
             "sha256_prefix":hasher.hexdigest()[:24],
             "video":True,"audio":"audio" in tracks,
             "android_receipt_verified":False,"media_retained":False}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as ex:
        print(json.dumps({"provider":"pornhub","status":"NOT_COMPLETE",
            "error_type":type(ex).__name__,
            "error_summary":re.sub(r"https?://\S+","[source-url]",str(ex))[:170],
            "android_receipt_verified":False}),flush=True)
        sys.exit(2)
