#!/usr/bin/env python3
"""Real selected-publisher original-file prebuild acceptance; no APK, no credentials.

Only runs the small first-party Rule34Video selected sample; never searches or
substitutes unrelated videos. Verifies bytes, first-party origin and ffprobe.
Temp file is deleted. No account state or queue is touched.
"""
import hashlib, html, json, re, shutil, subprocess, tempfile, urllib.parse, urllib.request
from html.parser import HTMLParser
from pathlib import Path

PAGE="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
USER_AGENT="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
MAX_BYTES=12_000_000

class SelectedLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[]
    def handle_starttag(self,tag,attrs):
        if tag=="a":
            d=dict(attrs)
            if d.get("href"):self.links.append(d["href"])

def get(url,referer=None):
    headers={"User-Agent":USER_AGENT,"Accept-Encoding":"identity"}
    if referer:headers["Referer"]=referer
    return urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=24)

def main():
    with get(PAGE) as response:
        assert response.status==200,(response.status,"watch page denied")
        body=response.read(1_400_000).decode("utf-8","replace")
    parsed=SelectedLinks();parsed.feed(body)
    links=[]
    for raw in parsed.links:
        link=urllib.parse.urljoin(PAGE,html.unescape(raw))
        uri=urllib.parse.urlsplit(link)
        if (uri.scheme=="https" and uri.hostname in ("rule34video.com","www.rule34video.com")
                and re.fullmatch(r"/get_file/[^?#]+",uri.path) and
                "download=true" in uri.query):
            links.append(link)
    assert links,"No selected publisher-provided originals"
    # The selected video is directly supplied from its own watch page;
    # choose a small published format to keep this finite.
    selected=min(links,key=lambda s:({"360p":0,"480p":1,"720p":2}.get(
        re.search(r"(?:360|480|720)p",s,re.I).group(0).lower(),3)
        if re.search(r"(?:360|480|720)p",s,re.I) else 4))
    sha=hashlib.sha256()
    with tempfile.TemporaryDirectory(prefix="asterion_rule34_real_original_") as tmp:
        dest=Path(tmp)/"publisher_original.mp4"
        with get(selected,PAGE) as response:
            mime=response.headers.get("Content-Type","").split(";")[0].lower()
            declared=int(response.headers.get("Content-Length","-1"))
            assert mime=="video/mp4",(response.status,mime)
            assert response.status==200,"Full read unexpectedly partial"
            assert 0<declared<=MAX_BYTES,("Original size unsupported for bounded test",declared)
            written=0
            with dest.open("wb") as fp:
                while True:
                    chunk=response.read(131072)
                    if not chunk:break
                    written+=len(chunk)
                    assert written<=MAX_BYTES,"File exceeded size bound"
                    fp.write(chunk);sha.update(chunk)
        assert written==declared==dest.stat().st_size,("Truncated original",written,declared)
        with dest.open("rb") as fp:
            magic=fp.read(16)
        assert magic[4:8]==b"ftyp","Original lacked MP4 signature"
        ffprobe=shutil.which("ffprobe")
        if not ffprobe:
            print(json.dumps({"status":"bytes_only","bytes":written,"declared":declared,
                "sha256_prefix":sha.hexdigest()[:20],"formats":len(links)}),flush=True)
            return
        proc=subprocess.run([ffprobe,"-v","error","-show_entries",
            "format=duration,format_name:stream=codec_type,codec_name",
            "-of","json",str(dest)],capture_output=True,text=True,timeout=20)
        assert proc.returncode==0,(proc.returncode,proc.stderr[:240])
        meta=json.loads(proc.stdout)
        duration=float(meta.get("format",{}).get("duration",0))
        tracks=[s.get("codec_type") for s in meta.get("streams",[])]
        assert duration>=3.0 and "video" in tracks,("Media not playable",duration,tracks)
        print(json.dumps({"status":"COMPLETE_FILE_VERIFIED","selected_video_id":4727779,
            "formats":len(links),"bytes":written,"declared":declared,
            "sha256_prefix":sha.hexdigest()[:20],"duration_seconds":round(duration,3),
            "video_stream":True,"audio_stream":"audio" in tracks}),flush=True)

if __name__=="__main__":
    main()
