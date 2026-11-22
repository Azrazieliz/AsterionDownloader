#!/usr/bin/env python3
"""Independent PC acceptance after exact selected first-party CDN redirect: full byte-and-duration receipt for a publisher-
advertised selected NotFans MP4. Public exact watch sample, no login bypass,
no arbitrary CDN, no permanent media retention. NEVER report success from HTML
or an HTTP range sample. This is a representative PC case, not owner's Android
selected job. The duration and SHA proof are printed only after full download.
"""
import hashlib,html,json,re,shutil,subprocess,sys,tempfile,time,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path

SELECTED="https://notfans.com/videos/125065/helloquqco-quqco7/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
MAX_BYTES=250_000_000
MAX_SECONDS=420
class Links(HTMLParser):
    def __init__(self):super().__init__();self.current=None;self.anchors=[]
    def handle_starttag(self,tag,attrs):
        if tag=="a":self.current={"attrs":dict(attrs),"text":""}
    def handle_data(self,data):
        if self.current is not None:self.current["text"]+=data
    def handle_endtag(self,tag):
        if tag=="a" and self.current is not None:
            self.anchors.append(self.current);self.current=None

def recorded(code,**fields):
    print(json.dumps({"provider":"notfans","pc_selected_page":SELECTED,
      "status":code,**fields},sort_keys=True),flush=True)

def open_request(opener,url,referer,headers=None):
    base={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"}
    base.update(headers or {})
    return opener.open(urllib.request.Request(url,headers=base),timeout=32)

def selected_download(page):
    article=urllib.parse.urlsplit(SELECTED)
    selected_id=re.fullmatch(r"/videos/([0-9]{1,12})/[a-z0-9][a-z0-9_-]{0,180}/?",article.path)
    if not selected_id:raise RuntimeError("PC target is not exact selected article")
    doc=Links();doc.feed(page)
    found=[]
    for a in doc.anchors:
        if not re.search(r"\bMP4\s+(\d{3,4})p\b",a["text"],re.I):continue
        quality=int(re.search(r"\bMP4\s+(\d{3,4})p\b",a["text"],re.I).group(1))
        url=urllib.parse.urljoin(SELECTED,html.unescape(a["attrs"].get("href","")))
        u=urllib.parse.urlsplit(url)
        valid=(u.scheme=="https" and u.hostname in {"notfans.com","www.notfans.com"}
          and not u.username and not u.password and not u.port and not u.fragment
          and re.fullmatch(r"/get_file/[0-9]{1,6}/[a-f0-9]{20,100}/[0-9]{1,12}/"+
              selected_id.group(1)+"/"+selected_id.group(1)+r"\.mp4/?",u.path,re.I)
          and urllib.parse.parse_qs(u.query).get("download")==["true"])
        if valid:found.append((quality,url))
    unique=list(dict.fromkeys(found))
    if not unique:raise RuntimeError("Exact public selected MP4 download anchor absent")
    top=max(q for q,_ in unique)
    winners=[u for q,u in unique if q==top]
    if len(winners)!=1:raise RuntimeError("Ambiguous highest selected quality")
    return winners[0],top

def accept():
    started=time.monotonic()
    opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor())
    with open_request(opener,SELECTED,SELECTED) as response:
        if response.status!=200:raise RuntimeError("Selected watch page HTTP "+str(response.status))
        if urllib.parse.urlsplit(response.url).hostname not in ("notfans.com","www.notfans.com"):
            raise RuntimeError("Selected watch page changed publisher")
        raw=response.read(1_500_000).decode("utf-8","replace")
    link,quality=selected_download(raw)
    text=html.unescape(re.sub(r"<[^>]*>"," ",raw))
    match=re.search(r"Duration\s*:\s*((?:[0-9]{1,3}:)?[0-9]{1,2}:[0-9]{2})",text,re.I)
    expected_duration=None
    if match:
        parts=[int(v) for v in match.group(1).split(":")]
        expected_duration=sum(x*60**i for i,x in enumerate(reversed(parts)))
    recorded("PUBLISHER_SELECTED_DOWNLOAD_FOUND",quality=quality,
             published_duration_seconds=expected_duration,full_file_verified=False)
    probe=shutil.which("ffprobe")
    if not probe:raise RuntimeError("ffprobe missing")
    with tempfile.TemporaryDirectory(prefix="asterion_pc_selected_") as directory:
        path=Path(directory)/"selected_original.mp4"
        with open_request(opener,link,SELECTED) as response:
            if response.status!=200:raise RuntimeError("Selected file HTTP "+str(response.status))
            # The *selected first-party download action* may legitimately
            # send the actual media bytes through a publisher CDN. Trust
            # only that exact selected anchor's HTTPS redirect chain; never
            # discover arbitrary media on the destination website. HTTP
            # length, MP4 signature, full transfer and exact selected duration
            # below still have to verify the ENTIRE original.
            destination=urllib.parse.urlsplit(response.url)
            import ipaddress
            hostname=destination.hostname or ""
            is_private=False
            try:
                addr=ipaddress.ip_address(hostname)
                is_private=not addr.is_global
            except ValueError:
                is_private=(hostname in ("localhost","localhost.localdomain") or
                    hostname.endswith(".local"))
            if (destination.scheme!="https" or not hostname or
                destination.username or destination.password or
                destination.port not in (None,443) or is_private):
                raise RuntimeError("Selected official MP4 redirected to an unsafe destination")
            if hostname not in ("notfans.com","www.notfans.com"):
                recorded("SELECTED_FIRST_PARTY_CDN_REDIRECT",media_host=hostname,
                    selected_article_attested=True,full_file_verified=False)
            mime=response.headers.get("Content-Type","").split(";")[0].lower()
            if mime not in ("video/mp4","application/octet-stream","binary/octet-stream"):
                raise RuntimeError("Selected file returned nonmedia MIME "+mime)
            declared=int(response.headers.get("Content-Length") or "0")
            if declared<=1024 or declared>MAX_BYTES:
                raise RuntimeError("Selected file has missing/unsafe total length")
            written=0
            digest=hashlib.sha256()
            with path.open("wb") as out:
                while True:
                    if time.monotonic()-started>MAX_SECONDS:
                        raise RuntimeError("Full file exceeded bounded transfer deadline")
                    blob=response.read(262144)
                    if not blob:break
                    written+=len(blob)
                    if written>declared or written>MAX_BYTES:
                        raise RuntimeError("Selected response exceeded declared original")
                    digest.update(blob);out.write(blob)
                    if written//(8*1024*1024)!=(written-len(blob))//(8*1024*1024):
                        recorded("TRANSFERRING_REAL_BYTES",received=written,expected=declared,
                            completed_original=False)
        if written!=declared or path.stat().st_size!=declared:
            raise RuntimeError("Full file byte count not equal HTTP declared total")
        with path.open("rb") as media:
            if media.read(12)[4:8]!=b"ftyp":
                raise RuntimeError("Full response is not ISO BMFF/MP4")
        tested=subprocess.run([probe,"-v","error","-of","json",
            "-show_entries","format=duration:stream=codec_type,codec_name",
            str(path)],capture_output=True,text=True,timeout=45)
        if tested.returncode!=0:raise RuntimeError("Full file FFprobe failed")
        data=json.loads(tested.stdout)
        seconds=float(data.get("format",{}).get("duration") or 0)
        streams=[s.get("codec_type") for s in data.get("streams",[])]
        if seconds<60 or "video" not in streams:
            raise RuntimeError("Selected media is not a validated full video")
        if expected_duration is not None and abs(seconds-expected_duration)>max(12,0.05*expected_duration):
            raise RuntimeError("Full file duration does not match selected article")
        recorded("COMPLETE_SELECTED_PC_FILE",full_file_verified=True,
            received_bytes=written,declared_bytes=declared,
            duration_seconds=round(seconds,3),published_duration_seconds=expected_duration,
            media_streams=streams,sha256=digest.hexdigest(),quality=quality,
            elapsed_seconds=round(time.monotonic()-started,2),
            temporary_media_deleted_after_test=True,android_verified=False)
    return 0

if __name__=="__main__":
    try:sys.exit(accept())
    except Exception as error:
        recorded("NOT_CERTIFIED",error_type=type(error).__name__,
                 reason=re.sub(r"https?://[^\s]+","[url]",str(error))[:180],
                 full_file_verified=False)
        sys.exit(2)
