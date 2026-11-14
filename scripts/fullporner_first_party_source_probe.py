#!/usr/bin/env python3
"""Inspect ONLY selected FullPorner's official player and redacted media keys."""
import re,urllib.parse,urllib.request
from html.parser import HTMLParser
PAGE="https://fullporner.com/watch/6af6fbcd2f7835591446cb0f"
class Frames(HTMLParser):
    def __init__(self):super().__init__();self.frames=[]
    def handle_starttag(self,tag,attrs):
        if tag=="iframe":
            self.frames.append(dict(attrs).get("src",""))
def fetch(addr,ref=None):
    h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"}
    if ref:h["Referer"]=ref
    with urllib.request.urlopen(urllib.request.Request(addr,headers=h),timeout=15) as r:
        return r.read(1200000).decode("utf8","replace")
def redact(s):
    s=re.sub(r"https?://[^\s'\"<>]+",lambda m:"[HTTPS:"+str(urllib.parse.urlsplit(m.group()).hostname)+"]",s)
    s=re.sub(r"[A-Za-z0-9_-]{48,}","[TOKEN]",s)
    return s.replace("\n"," ").replace("\r"," ")
page=fetch(PAGE);p=Frames();p.feed(page)
for f in p.frames:
    u=urllib.parse.urlsplit(urllib.parse.urljoin(PAGE,f))
    if u.hostname!="fullporner.com" or not re.fullmatch(r"/player/[0-9]{1,12}/[0-9]{1,4}/?",u.path):continue
    inner=fetch(u.geturl(),PAGE)
    print("FRAME",u.path,"CHARS",len(inner))
    for expr in (r"\.mp4",r"(?i)file\s*:",r"(?i)src\s*:",r"(?i)source\s*:",r"(?i)playback|player|duration"):
        for m in list(re.finditer(expr,inner))[:9]:
            print("KEY",expr,"CTX",redact(inner[max(0,m.start()-130):min(len(inner),m.end()+180)])[:360],flush=True)
