#!/usr/bin/env python3
"""Exact selected MXGS-1452 STREAM SB -> javclan.com JWPlayer HLS acceptance.

Never guesses a hoster or accepts unrelated ads. Reconstructs publisher
Base64 first-party shortcode, first-party reversed token and the exact
javclan.com embed, decodes that embed's P.A.C.K.E.R TEXT only, then verifies
publisher-declared HLS and optionally a full FFprobe-checked temporary file.
No APK/phone mutation. No signed media URLs in receipts.
"""
import base64,hashlib,html,json,re,shutil,subprocess,sys,tempfile,urllib.parse,urllib.request
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
    p=Script();p.feed(page)
    valid=set()
    packed_count=0
    for text in p.raw:
        if "eval(function(p,a,c,k,e,d)" not in text:continue
        decoded=deobfuscate(text)
        if not decoded:continue
        packed_count+=1
        for match in re.finditer(
            r"""(?i)['"]?\b(?:hls[234]|file)['"]?\s*:\s*['"](https?://[^"'<>\\\s]{12,2500})['"]""",
            decoded):
            link=match.group(1).replace("\\/","/")
            u=urllib.parse.urlsplit(link)
            if (u.scheme=="https" and u.hostname in ("javclan.com","www.javclan.com")
                and u.path.startswith("/stream/") and
                u.path.endswith(".m3u8") and not u.username and not u.password
                and not re.search(r"preview|teaser|sample|trailer",u.path,re.I)):
                valid.add(link)
    print(json.dumps({"provider":"jav_guru","stage":"OWNER_SB_PACKED_HLS",
        "selected_embed_host":"javclan.com","decoded_scripts":packed_count,
        "selected_hls_count":len(valid),"full_original_verified":False}),flush=True)
    if not valid:return None
    # Priority hls4/quality differences are irrelevant to identity: all
    # must originate in the single selected JWPlayer packed configuration.
    return sorted(valid)[0]

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
    if not WHEEL.is_file():raise RuntimeError("Bundled extractor wheel missing")
    sys.path.insert(0,str(WHEEL))
    import yt_dlp
    if yt_dlp.version.__version__!="2026.10.26":
        raise RuntimeError("Wrong production wheel")
    with tempfile.TemporaryDirectory(prefix="asterion_javguru_sb_full_") as folder:
        opts={"quiet":True,"no_warnings":True,
          "format":"bestvideo[height<=480]+bestaudio/best[height<=480]/best",
          "hls_use_mpegts":True,"merge_output_format":"mkv","noplaylist":True,
          "outtmpl":str(Path(folder)/"owner-selected.%(ext)s"),
          "http_headers":{"User-Agent":UA,"Referer":embed},
          "socket_timeout":23,"retries":1,"fragment_retries":1,
          "skip_unavailable_fragments":False,"concurrent_fragment_downloads":2}
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
        if "video" not in tracks or duration<60:
            raise RuntimeError("Not a verified complete playable selected original")
        digest=hashlib.sha256()
        with movie.open("rb") as stream:
            while chunk:=stream.read(1024*1024):digest.update(chunk)
        print(json.dumps({"provider":"jav_guru",
            "status":"COMPLETE_SELECTED_PC_FILE","bytes":size,
            "duration_s":round(duration,3),
            "sha256_prefix":digest.hexdigest()[:24],
            "video":True,"audio":"audio" in tracks,
            "android_receipt_verified":False,"temp_media_retained":False}),flush=True)
    return 0

if __name__=="__main__":
    try:sys.exit(full())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","status":"NOT_COMPLETE",
            "error_type":type(e).__name__,
            "reason":re.sub(r"https?://\S+","[url]",str(e))[:170]}),flush=True)
        sys.exit(3)
