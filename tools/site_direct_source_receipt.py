#!/usr/bin/env python3
"""Inspect/download only one site-published <video>/<source> HTTPS media.

This is an audit of an explicitly supplied public site. No credentials, DRM
circumvention, redirects to private IP ranges, or persistent media retention.
"""
from html.parser import HTMLParser
from urllib.parse import urljoin, urlsplit
from urllib.request import Request, urlopen
import argparse
import hashlib
import ipaddress
import re
import tempfile
import os

class Player(HTMLParser):
    def __init__(self):
        super().__init__()
        self.media=[]
    def handle_starttag(self,tag,attrs):
        x=dict(attrs)
        if tag in ("video","source") and x.get("src"):
            self.media.append(x["src"])

def allowed_https(url):
    u=urlsplit(url)
    if u.scheme!="https" or not u.hostname or u.username:return False
    try:
        ip=ipaddress.ip_address(u.hostname)
        if not ip.is_global:return False
    except ValueError: pass
    return True

def openurl(url,referer=None,method="GET",timeout=15):
    if not allowed_https(url):raise ValueError("unsafe URL")
    head={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"}
    if referer:head["Referer"]=referer
    req=Request(url,headers=head,method=method)
    return urlopen(req,timeout=timeout)

def audit(page,max_bytes):
    with openurl(page) as response:
        data=response.read(3_000_000).decode("utf-8",errors="replace")
        current=response.geturl()
    parser=Player();parser.feed(data)
    media=[urljoin(current,s) for s in parser.media]
    media=[u for u in media if allowed_https(u) and
           re.search(r"\.mp4(?:\?|$)",urlsplit(u).path,re.I)]
    print("VIDEO_PAGE_HOST",urlsplit(current).hostname)
    print("PUBLISHED_MEDIA_COUNT",len(media))
    if not media:return
    u=media[0]
    print("MEDIA_HOST",urlsplit(u).hostname)
    try:
        with openurl(u,current,"HEAD") as response:
            length=int(response.headers.get("Content-Length") or 0)
            kind=response.headers.get("Content-Type","")
            print("HEAD_STATUS",response.status,"CONTENT_TYPE",kind,
                  "CONTENT_LENGTH",length)
    except Exception as exc:
        print("HEAD_ERROR",str(exc)[:130])
        return
    if not 10000<length<=max_bytes:
        print("FULL_TRANSFER","SKIPPED_SIZE_LIMIT_OR_UNKNOWN")
        return
    sha=hashlib.sha256()
    n=0
    first=b""
    try:
        with openurl(u,current) as response:
            kind=response.headers.get("Content-Type","")
            if not ("video" in kind or "octet-stream" in kind):
                print("FULL_TRANSFER","FAILED_NONVIDEO",kind);return
            with tempfile.TemporaryFile() as f:
                while True:
                    chunk=response.read(262144)
                    if not chunk:break
                    n+=len(chunk)
                    if n>max_bytes:
                        print("FULL_TRANSFER","FAILED_EXCEEDED_LIMIT")
                        return
                    sha.update(chunk)
                    f.write(chunk)
                f.seek(0);first=f.read(12)
        if n==length and first[4:8]==b"ftyp":
            print("FULL_TRANSFER","PASS","BYTES",n,"MP4_FTYP",True,
                  "SHA256",sha.hexdigest(),"TEMP_MEDIA_DELETED",True)
        else:
            print("FULL_TRANSFER","FAILED","RECEIVED_BYTES",n,
                  "EXPECTED_BYTES",length,"IS_MP4",first[4:8]==b"ftyp")
    except Exception as exc:
        print("FULL_TRANSFER","ERROR",type(exc).__name__,str(exc)[:100])

if __name__=="__main__":
    p=argparse.ArgumentParser()
    p.add_argument("--url",required=True)
    p.add_argument("--max-bytes",type=int,default=30*1024*1024)
    x=p.parse_args()
    audit(x.url,x.max_bytes)
