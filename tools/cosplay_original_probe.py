#!/usr/bin/env python3
"""Inspect each approved Cosplay gallery for real image originals.

Never saves media or treats a preview as an original. Only tests status/MIME,
gallery-page ownership, full-resolution attributes and optional image links.
"""
from concurrent.futures import ThreadPoolExecutor
from html.parser import HTMLParser
from urllib.parse import urljoin,urlsplit
from urllib.request import urlopen,Request
import re
import json

SITES=[
 ("4KHD","https://www.4khd.com/"),
 ("Buondua","https://buondua.com/"),
 ("CosplayTele","https://cosplaytele.com/"),
 ("Kiutaku","https://kiutaku.com/"),
 ("Porn Pics","https://www.pornpics.com/"),
 ("Archivebate","https://archivebate.com/"),
 ("NotFans","https://notfans.com/"),
 ("Celeb.st","https://celeb.st/feed/hot")
]
EXT=re.compile(r"\.(?:png|jpe?g|webp|avif)(?:\?|$)",re.I)
class Probe(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links=[];self.images=[];self.direct=[]
    def handle_starttag(self,tag,attrs):
        at=dict(attrs)
        if tag=="a" and at.get("href"):
            self.links.append(at["href"])
            if EXT.search(at["href"]):self.direct.append(at["href"])
        if tag=="img":
            self.images.append(at)
def get(url,head=False):
    req=Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"},
                method="HEAD" if head else "GET")
    with urlopen(req,timeout=12) as response:
        content=b"" if head else response.read(2_500_000)
        return response.geturl(),response.status,response.headers,content
def canonical(host):
    return (host or "").lower().removeprefix("www.").removeprefix("m.")
def pick_page(html,base):
    parsed=Probe();parsed.feed(html)
    expected=canonical(urlsplit(base).hostname)
    for href in parsed.links[:1000]:
        url=urljoin(base,href);u=urlsplit(url)
        if u.scheme!="https" or canonical(u.hostname)!=expected:continue
        segs=[x for x in u.path.split("/") if x]
        if not segs or len(u.path)<6:continue
        if any(x in segs for x in ("category","categories","tag","tags","search","login")):continue
        if EXT.search(u.path):continue
        if re.match(r"/(?:\d{1,9}|(?:\d{4}/\d{1,2}/)?[a-z0-9-]{10,})/?$",u.path,re.I):
            return url,len(parsed.links)
    return None,len(parsed.links)
def probe(item):
    name,url=item;result={"site":name,"status":"UNAVAILABLE"}
    try:
        listing,code,_,body=get(url)
        result["listing_http"]=code
        result["final_host"]=urlsplit(listing).hostname
        page,links=pick_page(body.decode("utf8","replace"),listing)
        result["listing_links"]=links
        if not page:
            result["status"]="NO_GALLERY_PAGE";return result
        current,code,_,html=get(page)
        if canonical(urlsplit(current).hostname)!=canonical(urlsplit(listing).hostname):
            result["status"]="EXTERNAL_GALLERY_REDIRECT";return result
        parsed=Probe();parsed.feed(html.decode("utf8","replace"))
        result["page_http"]=code
        result["image_count"]=len(parsed.images)
        result["direct_image_links"]=len(parsed.direct)
        full=[]
        for img in parsed.images:
            for attr in ("data-original","data-full","data-full-src","data-highres","srcset"):
                raw=img.get(attr)
                if raw:
                    raw=raw.split(",")[-1].strip().split(" ")[0]
                    candidate=urljoin(current,raw)
                    if urlsplit(candidate).scheme=="https":full.append(candidate)
                    break
        result["highres_attributes"]=len(full)
        result["example_image_host"]=urlsplit(full[0]).hostname if full else None
        result["status"]="GALLERY_PAGE_FOUND"
    except Exception as e:
        result["status"]=type(e).__name__
    return result
if __name__=="__main__":
    with ThreadPoolExecutor(max_workers=4) as pool:
        result=list(pool.map(probe,SITES))
    for r in result:
        print(r["site"],r["status"],
              "IMAGES",r.get("image_count",0),
              "FULL_ATTRS",r.get("highres_attributes",0),
              "DIRECT_LINKS",r.get("direct_image_links",0),
              "IMAGE_HOST",r.get("example_image_host") or "")
