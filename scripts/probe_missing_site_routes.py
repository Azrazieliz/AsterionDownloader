#!/usr/bin/env python3
"""Inspect public publisher catalog route structure without downloading media.
Only public same-site anchor routes and counts are printed. No user session.
"""
import re,urllib.request,urllib.parse,urllib.error
from html.parser import HTMLParser
from collections import Counter
SITES={
 "eporner.com":"https://www.eporner.com/",
 "xxxi.porn":"https://xxxi.porn/",
 "helloporn.com":"https://helloporn.com/",
 "xxxclub.to":"https://xxxclub.to/",
 "cums.net":"https://cums.net/",
 "mp4hentai.com":"https://mp4hentai.com/",
 "onlyhentaistuff.com":"https://www.onlyhentaistuff.com/",
 "hentaibros.net":"https://hentaibros.net/",
 "pornpics.com":"https://www.pornpics.com/",
 "celeb.st":"https://celeb.st/feed/hot",
 "sushiscan.net":"https://sushiscan.net/",
 "mp4directs.com":"https://mp4directs.com/",
}
class Links(HTMLParser):
    def __init__(self):super().__init__();self.hrefs=[];self.title=False;self.titlebuf=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):self.hrefs.append(a["href"])
        if tag=="title":self.title=True
    def handle_endtag(self,tag):
        if tag=="title":self.title=False
    def handle_data(self,text):
        if self.title and len(self.titlebuf)<20:self.titlebuf.append(text[:70])
for host,url in SITES.items():
    try:
        req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept":"text/html","Accept-Encoding":"identity"})
        with urllib.request.urlopen(req,timeout=15) as r:
            status=r.status;body=r.read(900000).decode("utf8","replace")
        dom=Links();dom.feed(body)
        paths=[]
        for href in dom.hrefs:
            u=urllib.parse.urlsplit(urllib.parse.urljoin(url,href))
            if (u.scheme!="https" or u.hostname is None or
                not(u.hostname==host or u.hostname in ("www."+host,"m."+host))):continue
            path=u.path
            if path in ("","/") or len(path)>135:continue
            if path in paths:continue
            paths.append(path)
        frequencies=Counter()
        for path in paths:
            shape=re.sub(r"/[0-9]{2,12}(?=/|$)","/{n}",path)
            shape=re.sub(r"/[a-z0-9_-]{22,}(?=/|$)","/{slug}",shape)
            frequencies[shape.split("/")[1] if "/" in shape else shape]+=1
        print(host,"HTTP",status,"TITLE"," ".join(dom.titlebuf)[:60],
              "LINKS",len(paths),"TOP",frequencies.most_common(10),
              "EXAMPLES",paths[:16],flush=True)
    except Exception as e:
        print(host,type(e).__name__,str(e)[:100],flush=True)
