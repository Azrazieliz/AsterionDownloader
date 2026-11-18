#!/usr/bin/env python3
"""Probe only the Rapidgator link published by the owner's selected MP4Hentai Mirror.

Obtains the exact link via the already verified selected /files/DF7298C6/
page and its first-party clicked /getlink. No guessed file ID, password,
CAPTCHA automation, paid-tier request, redirect to unrelated media or URL leak.
This is source diagnosis, not full-file acceptance.
"""
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from html.parser import HTMLParser
from inspect_mp4hentai_mirror_normal_click import main as selected

UA = "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
REF = "https://www.mirrored.to/files/DF7298C6/?dl=0"

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        return None

class SelectedForms(HTMLParser):
    def __init__(self):
        super().__init__()
        self.forms = []
        self.links = []
    def handle_starttag(self, name, attrs):
        data = dict(attrs)
        if name == "form":
            self.forms.append({"method": data.get("method","get").lower(),
                               "action": data.get("action","")})
        elif name == "a" and data.get("href"):
            self.links.append({"href":data["href"],"title":data.get("title","")})

def main():
    raw = selected(return_selected_rapidgator=True)
    if not isinstance(raw,list) or len(set(raw)) != 1:
        print(json.dumps({"stage":"SELECTED_RAPIDGATOR",
                          "same_file_link_count":len(set(raw)) if isinstance(raw,list) else 0,
                          "verified":False}), flush=True)
        return 3
    target = raw[0]
    parsed = urllib.parse.urlsplit(target)
    if (parsed.scheme != "https" or parsed.hostname not in ("rapidgator.net","www.rapidgator.net")
            or parsed.username or parsed.password or parsed.port or
            not re.fullmatch(r"/file/[A-Za-z0-9_-]{6,100}(?:/[A-Za-z0-9%._-]{1,210})?/?",
                             parsed.path) or parsed.fragment):
        raise RuntimeError("Selected Rapidgator handoff is outside the trusted shape")
    opener = urllib.request.build_opener(NoRedirect)
    request = urllib.request.Request(target, headers={
        "User-Agent":UA, "Referer":REF, "Accept-Encoding":"identity",
        "Range":"bytes=0-4095"})
    try:
        with opener.open(request,timeout=25) as res:
            status=res.status; hdr=res.headers; data=res.read(250000)
    except urllib.error.HTTPError as ex:
        status=ex.code; hdr=ex.headers; data=ex.read(250000)
    mime=(hdr.get("Content-Type") or "").split(";")[0].lower()
    redirect=urllib.parse.urlsplit(urllib.parse.urljoin(target,hdr.get("Location",""))) if hdr.get("Location") else None
    html=data.decode("utf8","replace") if "html" in mime else ""
    parsed_html=SelectedForms()
    if html:parsed_html.feed(html)
    links=[]
    for item in parsed_html.links[:600]:
        candidate=urllib.parse.urlsplit(urllib.parse.urljoin(target,item["href"]))
        if candidate.scheme!="https" or not candidate.hostname:continue
        # Structural inventory only, no untrusted URL follows.
        if "download" in item["title"].lower() or "free" in item["title"].lower():
            links.append({"host":candidate.hostname,
                          "same_host":candidate.hostname==parsed.hostname,
                          "path_kind":candidate.path.split("/")[1][:24] if "/" in candidate.path else "",
                          "media_suffix":candidate.path.lower().endswith((".mp4",".mkv",".webm",".m3u8"))})
    print(json.dumps({
        "stage":"OWNER_SELECTED_RAPIDGATOR_FREE_ROUTE",
        "source_is_selected_clicked_mirror":True,
        "http":status,"mime":mime,"sample_bytes":len(data),
        "mp4_magic":data[4:8]==b"ftyp",
        "hls_magic":data.lstrip().startswith(b"#EXTM3U"),
        "attachment_header":bool(hdr.get("Content-Disposition")),
        "redirect_host":redirect.hostname if redirect else None,
        "redirect_to_direct_media":bool(redirect and redirect.path.lower().endswith((".mp4",".mkv",".webm",".m3u8"))),
        "html_form_methods":[x["method"] for x in parsed_html.forms[:10]],
        "html_form_action_hosts":sorted(set(urllib.parse.urljoin(target,x["action"]).split("/")[2]
            for x in parsed_html.forms[:10] if x["action"])),
        "captcha_or_challenge":bool(re.search(r"captcha|cf-turnstile|verify you are human|cloudflare",html,re.I)),
        "download_link_shapes":links[:12],"complete_file_verified":False
    }),flush=True)
    return 0 if status in (200,206) else 4

if __name__=="__main__":
    try:sys.exit(main())
    except Exception as exc:
        print(json.dumps({"stage":"OWNER_SELECTED_RAPIDGATOR_FREE_ROUTE",
                          "status":"NOT_VERIFIED","error_type":type(exc).__name__}),flush=True)
        sys.exit(6)
