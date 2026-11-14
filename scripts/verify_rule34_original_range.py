#!/usr/bin/env python3
"""Check real selected Rule34Video original transfer Range and MP4 ftyp.
Only reads 256 bytes; no complete media downloaded or stored.
"""
import html,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
PAGE="https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"
class A(HTMLParser):
    def __init__(self):super().__init__();self.anchors=[];self.current=None
    def handle_starttag(self,tag,attrs):
        if tag=="a":
            self.current={"attrs":dict(attrs),"text":""}
            self.anchors.append(self.current)
    def handle_data(self,value):
        if self.current and len(self.current["text"])<100:self.current["text"]+=value[:100]
    def handle_endtag(self,tag):
        if tag=="a":self.current=None
def request(url,ref=None,part=False):
    h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
       "Accept-Encoding":"identity"}
    if ref:h["Referer"]=ref
    if part:h["Range"]="bytes=0-255"
    req=urllib.request.Request(url,headers=h)
    with urllib.request.urlopen(req,timeout=18) as r:
        return r.status,r.url,r.headers,r.read(256 if part else 1400000)
status,_,_,content=request(PAGE)
doc=A();doc.feed(content.decode("utf8","replace"))
files=[]
for a in doc.anchors:
    src=urllib.parse.urljoin(PAGE,html.unescape(a["attrs"].get("href","")))
    p=urllib.parse.urlsplit(src)
    if p.scheme!="https" or p.hostname not in ("rule34video.com","www.rule34video.com") or not p.path.startswith("/get_file/"):continue
    if "download=true" not in p.query:continue
    if not re.search(r"(?i)\bmp4\s+[0-9]{3,4}p\b",a["text"]):continue
    files.append((a["text"].strip()[:24],src))
print("page",status,"original_formats",len(files),flush=True)
for label,url in files[:3]:
    try:
        code,final,h,payload=request(url,PAGE,part=True)
        mime=h.get("Content-Type","").split(";")[0]
        print("label",label,"status",code,
              "host",urllib.parse.urlsplit(final).hostname,
              "mime",mime,"range",h.get("Content-Range","")[:80],
              "ftyp",payload[4:8]==b"ftyp",
              "bytes_read",len(payload),flush=True)
    except urllib.error.HTTPError as e:
        print("label",label,"status",e.code,flush=True)
    except Exception as e:print("label",label,"error",type(e).__name__,flush=True)
