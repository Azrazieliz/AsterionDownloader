#!/usr/bin/env python3
"""Live HentaiBros exact selected WordPress-player verification.

No generic yt-dlp, no ads/preview fallback, no persistent media or cookies.
Fetch only the official selected post's wpfp_ data-item, media Range, and
optionally a complete <=12MB player original with real ffprobe.
"""
import html,json,re,subprocess,shutil,tempfile,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path

POST="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
MAX_FILE=12_000_000

class Players(HTMLParser):
    def __init__(self):
        super().__init__();self.stack=[];self.selected=[]
    def handle_starttag(self,tag,attrs):
        d=dict(attrs)
        is_related=tag in ("aside",) or any(k in (d.get("class","").lower())
            for k in ("related","recommended","sidebar","advertisement","ads"))
        excluded=bool(any(r[1] for r in self.stack)) or is_related
        if re.fullmatch(r"wpfp_\d{1,10}",d.get("id","")) and d.get("data-item") and not excluded:
            self.selected.append(d["data-item"])
        if tag not in ("source","img","br","input","hr","meta","link","wbr","area"):
            self.stack.append((tag,excluded))
    def handle_endtag(self,tag):
        for i in range(len(self.stack)-1,-1,-1):
            if self.stack[i][0]==tag:
                del self.stack[i:];break

def request(url,ref=None,byte_range=None):
    headers={"User-Agent":UA,"Accept-Encoding":"identity"}
    if ref:headers["Referer"]=ref
    if byte_range:headers["Range"]=byte_range
    return urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=22)

def main():
    with request(POST) as page:
        code=page.status;body=page.read(2_000_000).decode("utf-8","replace")
    p=Players();p.feed(body)
    print("selected_article_http",code,"html_bytes",len(body),
        "candidate_wordpress_players",len(p.selected),flush=True)
    if len(p.selected)!=1:
        print("FAIL_PLAYER_ATTRIBUTION",flush=True);return 2
    config=json.loads(html.unescape(p.selected[0]))
    arr=config.get("sources",[])
    if not isinstance(arr,list):print("FAIL_NO_SOURCES");return 3
    media=[]
    for entry in arr[:10]:
        if not isinstance(entry,dict):continue
        raw=entry.get("src","")
        resolved=urllib.parse.urljoin(POST,str(raw))
        url=urllib.parse.urlsplit(resolved)
        path=urllib.parse.quote(urllib.parse.unquote(url.path),safe="/%:@+-._~")
        safe=urllib.parse.urlunsplit((url.scheme,url.netloc,path,url.query,url.fragment))
        if url.scheme=="https" and url.hostname and (path.lower().endswith(".mp4") or
            entry.get("type","").lower()=="video/mp4"):
            media.append(safe)
    print("official_media_sources",len(media),flush=True)
    if not media:return 4
    selected=media[0]
    try:
        with request(selected,POST,"bytes=0-511") as stream:
            prefix=stream.read(512)
            mime=stream.headers.get("Content-Type","").split(";")[0].lower()
            cr=stream.headers.get("Content-Range","")
            size=int(cr.rsplit("/",1)[-1]) if "/" in cr and cr.rsplit("/",1)[-1].isdigit() else -1
            status=stream.status
    except urllib.error.HTTPError as exc:
        print("ORIGINAL_MEDIA_DENIED",exc.code,flush=True);return 5
    print("selected_original_range",status,"mime",mime,"bytes",len(prefix),
        "declared_total",size,"valid_mp4_ftyp",prefix[4:8]==b"ftyp",flush=True)
    if status!=206 or prefix[4:8]!=b"ftyp" or mime!="video/mp4":return 6
    if size<=0 or size>MAX_FILE:
        print("PARTIAL_MEDIA_ONLY_NOT_A_FULL_SUCCESS",flush=True);return 0
    with tempfile.TemporaryDirectory(prefix="asterion_real_hentaibros_") as d:
        path=Path(d)/"selected.mp4"
        try:
            with request(selected,POST) as full:
                if full.status!=200:
                    print("FULL_DOWNLOAD_NOT_200",full.status);return 7
                declared=int(full.headers.get("Content-Length","-1"))
                total=0
                with path.open("wb") as out:
                    while True:
                        block=full.read(131072)
                        if not block:break
                        total+=len(block)
                        if total>MAX_FILE:raise ValueError("bounded size exceeded")
                        out.write(block)
        except Exception as exc:
            print("FULL_DOWNLOAD_FAILED",type(exc).__name__,flush=True);return 8
        if total!=size or declared!=size or path.stat().st_size!=size:
            print("FULL_DOWNLOAD_TRUNCATED",total,declared,size,flush=True);return 9
        probe=shutil.which("ffprobe")
        if not probe:
            print("WHOLE_BYTES_VERIFIED_NO_FFPROBE",total,flush=True);return 0
        proc=subprocess.run([probe,"-v","error","-of","json",
            "-show_entries","format=duration:stream=codec_type",str(path)],
            capture_output=True,text=True,timeout=20)
        if proc.returncode:print("MEDIA_NOT_PLAYABLE",proc.stderr[:100]);return 10
        metadata=json.loads(proc.stdout)
        duration=float(metadata.get("format",{}).get("duration",0))
        has_video=any(s.get("codec_type")=="video" for s in metadata.get("streams",[]))
        print("COMPLETE_SELECTED_MEDIA_VERIFIED",total,round(duration,3),has_video,flush=True)
        return 0 if has_video and duration>=3 else 11

if __name__=="__main__":raise SystemExit(main())
