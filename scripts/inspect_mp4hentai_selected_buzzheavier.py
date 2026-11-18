#!/usr/bin/env python3
"""Only MP4Hentai owner's clicked Mirrored.to BuzzHeavier public file host.

No guessed file ID, unrelated links, CAPTCHA bypass or media save.
Discover the exact original through normal publisher-selected Mirror button
and its official /getlink/ HTML, then examine BuzzHeavier's real Download
controls, response MIME and content-range without revealing signed URLs.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from inspect_mp4hentai_mirror_normal_click import main as publisher_selected_mirror

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Controls(HTMLParser):
    def __init__(self):
        super().__init__();self.links=[];self.active=None
    def handle_starttag(self,tag,attrs):
        d=dict(attrs)
        if tag=="a" and d.get("href"):
            self.active={"url":d["href"],"text":"","class":d.get("class",""),
                "title":d.get("title",""),"download":"download" in d}
            self.links.append(self.active)
    def handle_data(self,txt):
        if self.active and len(self.active["text"])<170:
            self.active["text"]+=txt[:170]
    def handle_endtag(self,tag):
        if tag=="a":self.active=None

def main():
    direct=publisher_selected_mirror(return_selected_buzzheavier=True)
    if not isinstance(direct,list) or len(direct)!=1:
        raise RuntimeError("owner Mirrored selected BuzzHeavier link not unique")
    selected=direct[0]
    url=urllib.parse.urlsplit(selected)
    if(url.scheme!="https" or url.hostname not in
       ("buzzheavier.com","www.buzzheavier.com") or
       not re.fullmatch(r"/[A-Za-z0-9_-]{6,100}/?",url.path)):
        raise RuntimeError("not exact published selected BuzzHeavier hoster")
    request=urllib.request.Request(selected,headers={
      "User-Agent":UA,"Referer":"https://www.mirrored.to/files/DF7298C6/?dl=0",
      "Accept-Encoding":"identity"})
    with urllib.request.urlopen(request,timeout=26) as r:
        if r.status!=200:raise RuntimeError("selected hoster page not HTTP 200")
        document=r.read(1_000_000).decode("utf8","replace")
    dom=Controls();dom.feed(document)
    candidates=[]
    for item in dom.links:
        label=(item["text"]+" "+item["class"]+" "+item["title"]).lower()
        if not (item["download"] or re.search(
            r"download|download file|save file|get file",label)):continue
        link=urllib.parse.urlsplit(urllib.parse.urljoin(selected,html.unescape(item["url"])))
        if(link.scheme!="https" or not link.hostname or link.username or link.password):
            continue
        if link.hostname not in ("buzzheavier.com","www.buzzheavier.com"):
            continue
        if not link.path.startswith(url.path.rstrip("/")+"/"):
            continue
        candidates.append((link.geturl(),item))
    shapes=[{"host":urllib.parse.urlsplit(link).hostname,
        "route_depth":len([v for v in urllib.parse.urlsplit(link).path.split("/") if v]),
        "last_route_kind":urllib.parse.urlsplit(link).path.rstrip("/").split("/")[-1][:35],
        "download_attribute":attr["download"],
        "query_keys":sorted(urllib.parse.parse_qs(
            urllib.parse.urlsplit(link).query).keys())[:8]}
        for link,attr in candidates[:12]]
    print(json.dumps({"provider":"mp4hentai","stage":"EXACT_SELECTED_BUZZHEAVIER_PUBLIC_HOSTER",
        "selected_publisher_file":True,
        "html_chars":len(document),"download_controls":len(candidates),
        "control_shapes":shapes,
        "captcha_marker":bool(re.search(
            r"captcha|cf-turnstile|g-recaptcha|h-captcha",document,re.I)),
        "complete_original_verified":False}),flush=True)
    return 0 if candidates else 3

if __name__=="__main__":
    try:sys.exit(main())
    except urllib.error.HTTPError as ex:
        print(json.dumps({"provider":"mp4hentai",
           "status":"SELECTED_BUZZHEAVIER_HTTP_BLOCKED",
           "http":ex.code,
           "selected_host":urllib.parse.urlsplit(ex.url).hostname,
           "mime":ex.headers.get("Content-Type","").split(";")[0],
           "redirect_host":urllib.parse.urlsplit(
               ex.headers.get("Location","")).hostname,
           "complete_original_verified":False}),flush=True)
        sys.exit(4)
    except Exception as ex:
        print(json.dumps({"provider":"mp4hentai",
           "status":"SELECTED_BUZZHEAVIER_NOT_VERIFIED",
           "error_class":type(ex).__name__}),flush=True)
        sys.exit(4)
