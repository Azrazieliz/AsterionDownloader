#!/usr/bin/env python3
"""Read-only PC selected page diagnostics for already-approved TaiAV and OnScreens.

This prints only short code contexts around static media literals, DOM identity,
HTTPS first-party endpoint hosts, and HTTP status, not raw media URLs or tokens.
Never downloads video or follows advertising links.
"""
import json,re,urllib.parse,urllib.request
from html.parser import HTMLParser

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Links(HTMLParser):
    def __init__(self):
        super().__init__();self.a=[];self.cur=None
    def handle_starttag(self,t,attrs):
        a=dict(attrs)
        if t=="a" and a.get("href"):
            self.a.append(a["href"])
def fetch(url):
    q=urllib.request.Request(url,headers={"User-Agent":UA,"Accept":"text/html","Accept-Encoding":"identity"})
    with urllib.request.urlopen(q,timeout=18) as res:
        return res.status,res.url,res.read(2_000_000).decode("utf-8","replace")
def one(site,listing,pattern):
    code,base,html=fetch(listing)
    doc=Links();doc.feed(html)
    seen=[]
    for href in doc.a:
        url=urllib.parse.urljoin(base,href);u=urllib.parse.urlsplit(url)
        if u.scheme!="https" or not u.hostname or u.hostname.removeprefix("www.")!=site:continue
        if re.fullmatch(pattern,u.path,re.I) and url not in seen:seen.append(url)
    if not seen:return {"site":site,"error":"NO_SELECTED_ARTICLE"}
    status,page,html=fetch(seen[0])
    report={"site":site,"http":status,"html_size":len(html),"selected_path":urllib.parse.urlsplit(page).path}
    for token in (".m3u8",".mp4",".mpd","jwplayer","playlist","Hls","videojs","sources","iframe","data-file","data-video","og:video"):
        contexts=[]
        for m in list(re.finditer(re.escape(token),html,re.I))[:12]:
            piece=html[max(0,m.start()-180):min(len(html),m.end()+180)]
            piece=re.sub(r"https?:(?:\\/\\/|//)[^\\s\"'<>{}]{0,350}","[URL]",piece)
            piece=re.sub(r"(?:token|signature|key|hash|expires|authorization|auth)[=:][^\\s&;\"]+","SECRET=[REDACTED]",piece,flags=re.I)
            piece=re.sub(r"\\s+"," ",piece)
            contexts.append(piece[:340])
        report[token]={"count":len(list(re.finditer(re.escape(token),html,re.I))),"contexts":contexts[:5]}
    print(json.dumps(report,ensure_ascii=False),flush=True)
if __name__=="__main__":
    for args in [
        ("taiav.com","https://taiav.com/cn/",r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?"),
        ("onscreens.me","https://www.onscreens.me/",r"/[a-f0-9-]{36}/[a-z0-9-]{9,255}/?")
    ]:
        try:one(*args)
        except Exception as exc:print(json.dumps({"site":args[0],"error":type(exc).__name__}),flush=True)
