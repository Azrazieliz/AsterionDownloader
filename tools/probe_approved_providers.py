#!/usr/bin/env python3
"""Read-only public-surface audit for the exact approved Aloha source set.

No credentials, media payloads, paywalls, proxy workarounds, or bulk downloads.
A public-index PASS is only a discovery signal, never proof of full backfill.
"""
import argparse
import concurrent.futures as futures
import html.parser
import json
import re
import urllib.request
import urllib.error
from pathlib import Path
from datetime import datetime, timezone

CATALOG=Path(__file__).resolve().parents[1]/"app/src/main/java/com/azrael/pixivdumpsync/AlohaSources.kt"
EXTRA=[
 ("Patreon","https://www.patreon.com/","CREATORS"),
 ("FANBOX","https://www.fanbox.cc/","CREATORS"),
 ("Fantia","https://fantia.jp/","CREATORS"),
 ("Gumroad","https://gumroad.com/","ASSETS"),
 ("OnlyFans","https://onlyfans.com/","CREATORS"),
 ("Fansly","https://fansly.com/","CREATORS"),
 ("KoikatsuCards","https://koikatsucards.com/","ASSETS"),
 ("Pawchive","https://pawchive.pw/","CREATORS"),
 ("Kemono","https://kemono.cr/","CREATORS"),
 ("Coomer","https://coomer.st/","CREATORS"),
 ("F95Zone","https://f95zone.to/","GAMES"),
 ("Shionlib","https://shionlib.com/","BOOKS"),
 ("Anna's Archive","https://annas-archive.org/","BOOKS")
]
def catalog():
    data=CATALOG.read_text(encoding="utf-8")
    m=re.search(r'private val sourceRows="""(.*?)"""\.trimIndent',data,re.S)
    if not m:raise RuntimeError("Approved catalog missing")
    rows=[tuple(line.split("|")) for line in m.group(1).splitlines() if line.strip()]
    if len(rows)!=43:raise RuntimeError("Source catalog changed; refusing incomplete audit")
    return rows+EXTRA

class Signals(html.parser.HTMLParser):
    def __init__(self):
        super().__init__()
        self.anchors=0
        self.images=0
        self.next=0
        self.media=0
        self.login=0
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="img":self.images+=1
        if tag=="a":
            self.anchors+=1
            href=a.get("href","")
            if re.search(r'(?:\.mp4|\.webm|\.png|\.jpg|\.epub|\.zip|\.torrent|/post/|/gallery/|/video/|/album/)',href,re.I):
                self.media+=1
            if "next" in a.get("rel","") or a.get("class","").find("next")>=0:
                self.next+=1
            if "login" in href.lower() or "signin" in href.lower():
                self.login+=1

def probe(row,timeout=9):
    name,url,area=row
    status="ERROR";reason="";http=0;total=0;counts=Signals()
    try:
        request=urllib.request.Request(url,headers={
            "User-Agent":"Mozilla/5.0 (Android 17) AsterionProviderProbe/1.0",
            "Accept":"text/html,application/xhtml+xml"
        })
        with urllib.request.urlopen(request,timeout=timeout) as response:
            http=response.status
            mime=response.headers.get("Content-Type","").lower()
            data=response.read(192*1024)
            total=len(data)
        if "html" not in mime:
            status="NON_HTML";reason="Provider does not expose an HTML listing"
        else:
            counts.feed(data.decode("utf-8","replace"))
            status="PUBLIC_INDEX" if counts.media>0 else (
                "PUBLIC_LISTING" if counts.anchors>4 else "NO_LINKS")
            if counts.media==0:reason="No media/gallery links in bounded public HTML"
    except urllib.error.HTTPError as e:
        http=e.code
        status="AUTH_OR_BLOCK" if e.code in (401,402,403,429) else "HTTP_ERROR"
        reason="HTTP "+str(e.code)
    except Exception as e:
        status="NETWORK_ERROR"
        reason=type(e).__name__+(": "+str(e)[:100] if str(e) else "")
    return {"name":name,"category":area,"url":url,"status":status,
        "http":http,"html_bytes":total,"anchors":counts.anchors,"images":counts.images,
        "media_hints":counts.media,"pagination_hints":counts.next,
        "login_hints":counts.login,"reason":reason}

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--output",type=Path,required=True)
    p.add_argument("--workers",type=int,default=8)
    p.add_argument("--timeout",type=float,default=9.0)
    a=p.parse_args()
    rows=catalog()
    with futures.ThreadPoolExecutor(max_workers=max(1,min(a.workers,12))) as pool:
        results=list(pool.map(lambda r:probe(r,a.timeout),rows))
    counts={}
    for item in results:counts[item["status"]]=counts.get(item["status"],0)+1
    output={"created_utc":datetime.now(timezone.utc).isoformat(),
        "scope":"Public HTML sampling only, not authentication or full backfill validation",
        "approved_sites":len(rows),"summary":counts,"sites":results}
    a.output.parent.mkdir(parents=True,exist_ok=True)
    a.output.write_text(json.dumps(output,indent=2,ensure_ascii=False),encoding="utf-8")
    print("APPROVED_PROVIDER_PROBE",len(rows),counts,flush=True)
    print("AUDIT_REPORT",a.output.resolve(),flush=True)

if __name__=="__main__":main()
