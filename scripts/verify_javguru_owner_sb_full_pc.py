#!/usr/bin/env python3
"""Exact selected MXGS-1452 STREAM SB -> javclan.com JWPlayer HLS acceptance.

Never guesses a hoster or accepts unrelated ads. Reconstructs publisher
Base64 first-party shortcode, first-party reversed token and the exact
javclan.com embed, decodes that embed's P.A.C.K.E.R TEXT only, then verifies
publisher-declared HLS and optionally a full FFprobe-checked temporary file.
No APK/phone mutation. No signed media URLs in receipts.
"""
import base64,hashlib,html,json,re,shutil,subprocess,sys,tempfile,urllib.parse,urllib.request,time
from pathlib import Path
from verify_javguru_exact_selected_hls import Doc,get,value,UA
from verify_roshy_selected_packed_hls import Script,deobfuscate
WATCH="https://jav.guru/1077276/mxgs-1452-meruty-kiss-drooling-saliva-melting-french-kiss-full-course-sato-meru/"
WHEEL=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"

def selected_player():
    code,selected,page=get(WATCH,WATCH)
    if code!=200 or urllib.parse.urlsplit(selected).hostname!="jav.guru":
        raise RuntimeError("Exact publisher post inaccessible")
    d=Doc();d.feed(page)
    sb=[x for x in d.buttons if x["label"].strip().upper()=="STREAM SB"]
    if len(sb)!=1:raise RuntimeError("No unique exact selected SB shortcode")
    key=sb[0]["key"]
    configs=[]
    for script in d.code:
        for match in re.finditer(
            r"\bvar\s+([a-z]{10})\s*=\s*(\{[^}]{20,2500}\})",script,re.S):
            if match.group(1)!=key:continue
            obj=json.loads(match.group(2))
            url=base64.b64decode(obj["iframe_url"],validate=True).decode("utf8")
            u=urllib.parse.urlsplit(url)
            if (u.scheme=="https" and u.hostname=="jav.guru" and
                u.path=="/searcho/" and
                re.fullmatch(r"[a-z]{2}=[a-fA-F0-9]{10,150}(?:&bg=https?%3A%2F%2F[^&]{1,400})?",u.query)):
                configs.append(url)
    if len(configs)!=1:raise RuntimeError("Not one verified selected SB first-party source")
    wrapper=configs[0]
    code,redirect,markup=get(wrapper,WATCH)
    d=Doc();d.feed(markup)
    scripts=[x for x in d.code if "window.cfg" in x and "fullToken.rev()" in x]
    if len(scripts)!=1:raise RuntimeError("Selected SB relay script missing")
    js=scripts[0]
    cid=value(js,"cid");base=value(js,"base");rtype=value(js,"rtype")
    m=re.search(r"\bkeys\s*:\s*\[\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\]",js)
    if not(cid and cid in d.nodes and base=="https://jav.guru/searcho/"
           and rtype in ("e","v","s","x","t","h","o") and m):
        raise RuntimeError("SB publisher relay has unsupported source schema")
    target=base+"?"+rtype+"r="+("".join(d.nodes[cid][k] for k in m.groups())[::-1])
    code,embed,page=get(target,wrapper,950000)
    host=urllib.parse.urlsplit(embed).hostname
    if code!=200 or host!="javclan.com":
        raise RuntimeError("Selected SB did not resolve javclan.com original player")
    return embed,page

def selected_hls(embed,page):
    """Actual owner-selected SB JWPlayer links object, never generic URLs.

    Its published hls3 points to a .txt endpoint and its hls2 is a signed
    *.premilkyway.com HLS manifest. Only the exact selected packed script
    may authorize the CDN media, not ads elsewhere in the embedding page.
    """
    p=Script();p.feed(page)
    valid=set()
    decoded_count=0
    for packed in p.raw:
        if "eval(function(p,a,c,k,e,d)" not in packed:continue
        source=deobfuscate(packed)
        if not source:continue
        decoded_count+=1
        if not re.search(r"""jwplayer\(["']vplayer["']\)\.setup\(""",source) or not re.search(
            r"file\s*:\s*links\.hls4\s*\|\|\s*links\.hls3\s*\|\|\s*links\.hls2",source):
            continue
        matches=list(re.finditer(r"\bvar\s+links\s*=\s*",source))
        if len(matches)!=1:continue
        try:
            obj,_=json.JSONDecoder().raw_decode(
                source[matches[0].end():].lstrip())
        except (TypeError,ValueError):continue
        if not isinstance(obj,dict):continue
        hls=obj.get("hls2","")
        if not isinstance(hls,str):continue
        hls=hls.replace("\\/","/")
        uri=urllib.parse.urlsplit(hls)
        if(uri.scheme!="https" or not uri.hostname or
           not uri.hostname.endswith(".premilkyway.com") or
           not re.fullmatch(r"/hls2/[A-Za-z0-9_,./-]{20,650}/master\.m3u8",uri.path,re.I)
           or not uri.query or uri.username or uri.password or ".." in uri.path or
           re.search(r"preview|teaser|sample|trailer",uri.path,re.I)):
            continue
        valid.add(hls)
    print(json.dumps({"provider":"jav_guru","stage":"OWNER_SB_PACKED_HLS",
        "selected_embed_host":"javclan.com","decoded_scripts":decoded_count,
        "selected_hls_count":len(valid),"full_original_verified":False}),flush=True)
    return next(iter(valid)) if len(valid)==1 else None

def full():
    embed,page=selected_player()
    hls=selected_hls(embed,page)
    if not hls:raise RuntimeError("No publisher-attested selected SB HLS")
    request=urllib.request.Request(hls,headers={
        "User-Agent":UA,"Referer":embed,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(request,timeout=26) as response:
        header=response.read(100000)
        success=response.status==200 and header.lstrip().startswith(b"#EXTM3U")
        print(json.dumps({"provider":"jav_guru","stage":"ACTUAL_SELECTED_SB_HLS",
            "http":response.status,"manifest_bytes":len(header),
            "has_extm3u":success}),flush=True)
        if not success:return 2
    # The selected owner post publishes a 480p full-length rendition, not a
    # short advertisement. Its #EXTINF playlist duration is the authoritative
    # baseline for a completed-file receipt, never just a 60-second minimum.
    base=urllib.parse.urlsplit(hls)
    lines=header.decode("utf8","replace").splitlines()
    variants=[]
    for index,entry in enumerate(lines[:-1]):
        if "#EXT-X-STREAM-INF" not in entry:continue
        quality=re.search(r"RESOLUTION=[0-9]+x([0-9]+)",entry)
        if not quality or int(quality.group(1))!=480:continue
        dest=urllib.parse.urlsplit(urllib.parse.urljoin(hls,lines[index+1].strip()))
        if dest.scheme!="https" or dest.hostname!=base.hostname or not dest.path.endswith(".m3u8"):
            continue
        variants.append(dest.geturl())
    if len(variants)!=1:
        raise RuntimeError("Selected owner full-length 480p HLS variant ambiguous")
    child_req=urllib.request.Request(variants[0],headers={
        "User-Agent":UA,"Referer":hls,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(child_req,timeout=25) as response:
        published=response.read(190000).decode("utf8","replace")
        if response.status!=200 or not published.startswith("#EXTM3U"):
            raise RuntimeError("Selected signed owner variant did not publish HLS")
    expected_seconds=sum(float(v) for v in re.findall(
        r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",published))
    if expected_seconds<300 or expected_seconds>15000:
        raise RuntimeError("Selected publisher's full-length runtime is unavailable")
    print(json.dumps({"provider":"jav_guru","stage":"EXPECTED_SELECTED_FULL_RUNTIME",
        "selected_quality_height":480,
        "published_playlist_duration_s":round(expected_seconds,2),
        "published_segment_count":published.count("#EXTINF:")}),flush=True)
    if not WHEEL.is_file():raise RuntimeError("Bundled extractor wheel missing")
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    if yt_dlp.version.__version__!="2026.10.26":
        raise RuntimeError("Wrong production wheel")
    stage=Path("D:/AsterionControlRelease/diagnostics")
    if not stage.is_dir() or shutil.disk_usage(stage).free < 2000000000:
        raise RuntimeError("PC_MEDIA_STAGING_CAPACITY_BLOCKED: selected-original full file needs free space on D; do not use C system TEMP")
    started=time.monotonic()
    last_progress=[started]
    def on_progress(item):
        if item.get("status") not in ("downloading","finished"):return
        now=time.monotonic()
        if item.get("status")!="finished" and now-last_progress[0]<90:return
        last_progress[0]=now
        print(json.dumps({"provider":"jav_guru","stage":"SELECTED_FULL_PC_PROGRESS",
            "status":item.get("status"),
            "bytes_received":int(item.get("downloaded_bytes") or 0),
            "estimated_total_bytes":int(item.get("total_bytes_estimate") or
                item.get("total_bytes") or 0),
            "elapsed_s":round(now-started,1),
            "full_original_verified":False}),flush=True)
    with tempfile.TemporaryDirectory(prefix="asterion_javguru_sb_full_",dir=stage) as folder:
        opts={"quiet":True,"no_warnings":True,
          "format":"bestvideo[height<=480]+bestaudio/best[height<=480]/best",
          "hls_use_mpegts":True,"merge_output_format":"mkv","noplaylist":True,
          "outtmpl":str(Path(folder)/"owner-selected.%(ext)s"),
          "http_headers":{"User-Agent":UA,"Referer":embed},
          "socket_timeout":23,"retries":1,"fragment_retries":1,
          "skip_unavailable_fragments":False,"concurrent_fragment_downloads":8,
          "progress_hooks":[on_progress]}
        with yt_dlp.YoutubeDL(opts) as dl:
            dl.extract_info(hls,download=True)
        movies=[x for x in Path(folder).iterdir()
                if x.is_file() and x.suffix.lower() in (".mkv",".mp4",".ts",".webm")]
        if len(movies)!=1:raise RuntimeError("No unique complete selected movie")
        movie=movies[0]
        size=movie.stat().st_size
        if size<1_000_000:raise RuntimeError("Selected movie too small")
        probe=shutil.which("ffprobe")
        if not probe:raise RuntimeError("FFprobe unavailable")
        receipt=subprocess.run([probe,"-v","error",
            "-show_entries","format=duration:stream=codec_type",
            "-of","json",str(movie)],capture_output=True,text=True,timeout=40)
        if receipt.returncode!=0:raise RuntimeError("Media did not pass FFprobe")
        info=json.loads(receipt.stdout)
        duration=float(info.get("format",{}).get("duration") or 0)
        tracks=[s.get("codec_type") for s in info.get("streams",[])]
        print(json.dumps({"provider":"jav_guru",
            "stage":"WHOLE_FILE_FFPROBE_BEFORE_ACCEPTANCE",
            "file_bytes":size,"duration_s":round(duration,3),
            "publisher_playlist_duration_s":round(expected_seconds,3),
            "stream_kinds":tracks,
            "duration_match":bool(
                0.975*expected_seconds<=duration<=1.025*expected_seconds),
            "video_present":"video" in tracks,
            "audio_present":"audio" in tracks,
            "complete_original_verified":False}),flush=True)
        if "video" not in tracks or "audio" not in tracks or (
                not 0.975*expected_seconds<=duration<=1.025*expected_seconds):
            raise RuntimeError("Not the publisher's full selected movie with audio")
        digest=hashlib.sha256()
        with movie.open("rb") as stream:
            while chunk:=stream.read(1024*1024):digest.update(chunk)
        print(json.dumps({"provider":"jav_guru",
            "status":"COMPLETE_SELECTED_PC_FILE","bytes":size,
            "duration_s":round(duration,3),
            "sha256_prefix":digest.hexdigest()[:24],
            "video":True,"audio":"audio" in tracks,
            "selected_rendition_height":480,
            "published_playlist_duration_s":round(expected_seconds,2),
            "android_receipt_verified":False,"temp_media_retained":False}),flush=True)
    return 0

if __name__=="__main__":
    try:sys.exit(full())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","status":"NOT_COMPLETE",
            "error_type":type(e).__name__,
            "reason":re.sub(r"https?://\S+","[url]",str(e))[:170]}),flush=True)
        sys.exit(3)
