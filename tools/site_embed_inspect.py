#!/usr/bin/env python3
"""Report site-owned page discovery and embedded-player host evidence only."""
import argparse
from html.parser import HTMLParser
import urllib.parse
import urllib.request
import re

class Inspector(HTMLParser):
    def __init__(self):
        super().__init__()
        self.anchors = []
        self.players = []
        self.meta = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "a" and a.get("href"):
            self.anchors.append(a["href"])
        if tag in ("iframe", "video", "source") and a.get("src"):
            self.players.append((tag, a["src"]))
        if tag == "meta" and (a.get("property") or a.get("name")):
            prop = (a.get("property") or a.get("name") or "").lower()
            if "video" in prop or "player" in prop:
                self.meta.append(a.get("content",""))

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        raw = resp.read(3_000_000)
        return resp.geturl(), raw.decode("utf-8", errors="replace")

def host_or_empty(url):
    return urllib.parse.urlsplit(url).hostname or ""

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--listing")
    p.add_argument("--path-regex")
    p.add_argument("--video-url")
    o=p.parse_args()
    if o.video_url:
        selected=o.video_url
    else:
        if not o.listing or not o.path_regex:
            p.error("Provide --video-url or both --listing and --path-regex")
        root,html=fetch(o.listing)
        doc=Inspector();doc.feed(html)
        wanted=re.compile(o.path_regex,re.I)
        selected=None
        for href in doc.anchors:
            u=urllib.parse.urljoin(root,href)
            parsed=urllib.parse.urlsplit(u)
            if parsed.scheme!="https" or parsed.hostname!=host_or_empty(root):continue
            if wanted.search(parsed.path):
                selected=u;break
        print("LISTING_HOST",host_or_empty(root),"CANDIDATES",len(doc.anchors))
        if not selected:
            print("NO_RECOGNIZED_INDIVIDUAL_VIDEO_PAGE")
            return
    actual,page=fetch(selected)
    print("PAGE_HOST",host_or_empty(actual),"HTML_BYTES",len(page))
    detail=Inspector();detail.feed(page)
    print("PLAYERS",len(detail.players),"VIDEO_META",len(detail.meta))
    seen=set()
    for tag,raw in detail.players:
        u=urllib.parse.urljoin(actual,raw)
        parsed=urllib.parse.urlsplit(u)
        if parsed.scheme!="https" or not parsed.hostname:continue
        marker=(tag,parsed.hostname)
        if marker in seen:continue
        seen.add(marker)
        print("PLAYER_TAG",tag,"HOST",parsed.hostname,"MEDIA_SUFFIX",bool(re.search(
            r"\.(?:mp4|m3u8|webm)(?:\?|$)",parsed.path,re.I)))
    for raw in detail.meta:
        u=urllib.parse.urljoin(actual,raw)
        if urllib.parse.urlsplit(u).scheme=="https":
            print("META_PLAYER_HOST",host_or_empty(u))
    print("IN_PAGE_MEDIA_MARKERS",
          len(re.findall(r"\.(?:mp4|m3u8|webm)(?:\?|[\"'])",page,re.I)))
if __name__=="__main__":main()
