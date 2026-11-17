#!/usr/bin/env python3
"""Find the owner's screenshot MXGS-1452 via JAV Guru's own public search.

Only the exact same-site selected article may proceed to the existing verified
STREAM shortcode -> first-party /searcho/ -> LU HLS source probe. No guessed
title/URL, unrelated cards, media capture, login or Android change.
"""
import html, json, re, sys, urllib.parse, urllib.request, urllib.error
from html.parser import HTMLParser
import verify_javguru_exact_selected_hls as verified
SEARCH="https://jav.guru/?s=MXGS-1452"
class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.items=[]
        self.link=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):
            self.link={"href":a["href"],"text":""}
            self.items.append(self.link)
    def handle_data(self,txt):
        if self.link and len(self.link["text"])<300:
            self.link["text"]+=txt
    def handle_endtag(self,tag):
        if tag=="a":self.link=None

def main():
    req=urllib.request.Request(SEARCH,headers={
        "User-Agent":verified.UA,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(req,timeout=25) as r:
        if r.status!=200:raise RuntimeError("Publisher search denied")
        markup=r.read(1_000_000).decode("utf8","replace")
    doc=Links();doc.feed(markup)
    selected={}
    for item in doc.items:
        raw=urllib.parse.urljoin(SEARCH,html.unescape(item["href"]))
        url=urllib.parse.urlsplit(raw)
        if(url.scheme!="https" or url.hostname not in ("jav.guru","www.jav.guru") or
           url.query or url.fragment or not
           re.fullmatch(r"/[0-9]{4,10}/[a-z0-9][a-z0-9_-]{3,240}/?",url.path,re.I)):
            continue
        if not re.search(r"mxgs[-_ ]?1452",url.path+" "+item["text"],re.I):
            continue
        selected[raw]=item["text"][:150]
    print(json.dumps({"provider":"jav_guru","stage":"OWNER_TITLE_SOURCE_DISCOVERY",
        "query_code":"MXGS-1452","matching_first_party_articles":len(selected),
        "searched_site":"jav.guru"}),flush=True)
    if len(selected)!=1:return 2
    verified.WATCH=next(iter(selected))
    return verified.main()

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as error:
        print(json.dumps({"provider":"jav_guru","status":"OWNER_SELECTION_NOT_VERIFIED",
            "type":type(error).__name__,
            "message":re.sub(r"https?://[^\\s]+","[redacted]",str(error))[:180]}),flush=True)
        sys.exit(4)
