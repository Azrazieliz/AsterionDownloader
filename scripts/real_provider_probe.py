#!/usr/bin/env python3
"""Live, bounded website probes. No user credentials, no automatic video downloads.
Uses actual public provider URLs and returns evidence, not invented successes.
A real image can be downloaded/verified; video media is range-probed only.
"""
import concurrent.futures
import hashlib
from html.parser import HTMLParser
import json
import os
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

UA = ("Mozilla/5.0 (Linux; Android 17; Mobile) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
OUT = "real-provider-results.json"
TARGETS = {
    "Jav Guru": "https://jav.guru/",
    "Jav Guru detail": "https://jav.guru/1076811/mukc-135-off-pako-creampie-breeding-photo-session-where-we-defile-a-beautiful-pure-idol-whos-obedient-like-a-doll-however-we-please-shitara-yuuhi/",
    "Kiutaku": "https://kiutaku.com/",
    "Kiutaku gallery": "https://kiutaku.com/7440",
    "Rule34Video": "https://rule34video.com/",
    "Archivebate": "https://archivebate.com/",
    "OneJAV torrent index": "https://onejav.com/",
    "FreeWebNovel book": "https://freewebnovel.com/novel/dual-cultivation-novel",
    "NovelBuddy book": "https://novelbuddy.me/novel/dual-cultivation",
    "ReadNovelFull": "https://readnovelfull.com/",
}
MAX_PAGE = 1600000
TIMEOUT = 14

class Parse(HTMLParser):
    def __init__(self):
        super().__init__()
        self.anchors = []
        self.images = []
        self.players = []
        self.scripts = []
        self._href = None
        self._title = None
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "a":
            self.anchors.append(attrs.get("href", ""))
        elif tag in ("img", "source", "video", "iframe"):
            keys = ("src", "data-src", "data-original", "data-lazy-src",
                    "data-poster", "poster", "srcset", "data-srcset")
            candidates = [attrs[k] for k in keys if attrs.get(k)]
            if tag == "img":
                self.images.extend(candidates)
            else:
                self.players.extend(candidates)
        elif tag == "script":
            if attrs.get("src"):
                self.scripts.append(attrs["src"])

def fetch(url, *, limit=MAX_PAGE, range_header=None, referer=None):
    headers = {"User-Agent": UA, "Accept-Language": "en-US,en;q=0.9",
               "Accept": "*/*", "Accept-Encoding": "identity"}
    if range_header:
        headers["Range"] = range_header
    if referer:
        headers["Referer"] = referer
    # Publisher media paths legitimately contain Unicode names (Kiutaku/Mitaku).
    # urllib's HTTP request target must be ASCII percent-encoded.
    request_url = urllib.parse.quote(url, safe=":/?#[]@!    req = urllib.request.Request(url, headers=headers)'()*+,;=%")
    req = urllib.request.Request(request_url, headers=headers)
    started = time.monotonic()
    try:
        response = urllib.request.urlopen(req, timeout=TIMEOUT)
    except urllib.error.HTTPError as error:
        response = error
    except Exception as error:
        return dict(http=0, error=type(error).__name__ + ": " + str(error)[:180],
                    elapsed_s=round(time.monotonic()-started, 2), bytes=0)
    with response:
        try:
            data = response.read(limit + 1)
        except Exception as error:
            return dict(http=getattr(response, "status", getattr(response, "code", 0)),
                        error="read:" + type(error).__name__ + ": " + str(error)[:120],
                        bytes=0)
        mime = response.headers.get("Content-Type", "").split(";")[0].strip().lower()
        result = dict(http=response.status, mime=mime,
            bytes=min(len(data), limit), truncated=len(data) > limit,
            content_length=response.headers.get("Content-Length", ""),
            accept_ranges=response.headers.get("Accept-Ranges", ""),
            content_range=response.headers.get("Content-Range", ""),
            elapsed_s=round(time.monotonic()-started, 2),
            final_host=urllib.parse.urlsplit(response.url).hostname or "",
            sha256=hashlib.sha256(data[:limit]).hexdigest()[:24])
        return result, data[:limit]

def first_party(host, url):
    target = (urllib.parse.urlsplit(url).hostname or "").lower()
    normalized = host.lower().removeprefix("www.")
    return target == normalized or target.endswith("." + normalized)

def absolute(base, raw):
    raw = raw.strip().split(" ")[0]
    if raw.startswith(("javascript:", "data:", "blob:", "#")):
        return ""
    url = urllib.parse.urljoin(base, raw)
    split = urllib.parse.urlsplit(url)
    if split.scheme not in ("http", "https") or not split.hostname:
        return ""
    return url

def render_dom(url):
    chrome = next((shutil.which(x) for x in
        ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")
        if shutil.which(x)), None)
    if not chrome:
        return dict(available=False)
    try:
        run = subprocess.run([chrome, "--headless", "--no-sandbox",
             "--disable-gpu", "--disable-dev-shm-usage",
             "--virtual-time-budget=9000", "--dump-dom", url],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=24, check=False)
        page = run.stdout[:MAX_PAGE]
        return dict(available=True, ok=bool(page and b"<html" in page.lower()),
                    bytes=len(page), exit=run.returncode,
                    dom=page.decode("utf8", "replace"))
    except Exception as error:
        return dict(available=True,ok=False,error=str(error)[:200])

def main():
    os.makedirs("real-provider-evidence", exist_ok=True)
    report = {}
    # Per-site requests are concurrent; no fixed site takes down other providers.
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        future = {pool.submit(fetch, url):name for name,url in TARGETS.items()}
        for job in concurrent.futures.as_completed(future):
            name = future[job]
            try:
                response = job.result()
                meta, body = response if isinstance(response, tuple) else (response, b"")
                page = body.decode("utf8", "replace")
                parsed = Parse()
                parsed.feed(page)
                host = urllib.parse.urlsplit(TARGETS[name]).hostname or ""
                details = dict(meta, anchors=len(parsed.anchors),images=len(parsed.images),
                    video_player_elements=len(parsed.players),
                    first_party_links=sum(bool(absolute(TARGETS[name],a)) and
                        first_party(host,absolute(TARGETS[name],a))
                        for a in parsed.anchors),
                    age_or_gate=bool(re.search(r"(?i)verify you are human|cloudflare|"
                        r"enable javascript|enter only if you are over 18|age verification",page)),
                    sample_hrefs=[urllib.parse.urlsplit(absolute(TARGETS[name],x)).path
                        for x in parsed.anchors if absolute(TARGETS[name],x) and
                        first_party(host,absolute(TARGETS[name],x))][:5],
                    sample_img_hosts=list(dict.fromkeys(
                        (urllib.parse.urlsplit(absolute(TARGETS[name],x)).hostname or "")
                        for x in parsed.images if absolute(TARGETS[name],x)))[:8],
                    sample_img_paths=[urllib.parse.urlsplit(
                        absolute(TARGETS[name],x)).path[:170]
                        for x in parsed.images if absolute(TARGETS[name],x)][:8])
                report[name] = details
                # Used only for bounded real-media/HTML followups, never persisted.
                report[name]["_page"] = page
                report[name]["_images"] = parsed.images
                report[name]["_players"] = parsed.players
                report[name]["_anchors"] = parsed.anchors
            except Exception as error:
                report[name] = dict(error=type(error).__name__ + ": " + str(error)[:150])

    # Download a genuine Kiutaku publisher photo from its real image host
    # (not a homepage montage), validate magic bytes, and discard it.
    gallery = report.get("Kiutaku gallery", {})
    candidate = next((absolute(TARGETS["Kiutaku gallery"], raw)
        for raw in gallery.get("_images", [])
        if "mitaku.net" in absolute(TARGETS["Kiutaku gallery"], raw)), "")
    if not candidate:
        candidate = next((absolute(TARGETS["Kiutaku gallery"], raw)
            for raw in gallery.get("_anchors", [])
            if "mitaku.net" in absolute(TARGETS["Kiutaku gallery"], raw)), "")
    if candidate:
        # Mitaku is the image publisher behind Kiutaku. The app's loader
        # already sends this host's own Referer (not kiutaku.com).
        # Confirm the actual CDN behavior using the same request headers.
        fetched = fetch(candidate,limit=4_000_000,
            referer="https://mitaku.net/")
        meta,data = fetched if isinstance(fetched,tuple) else (fetched,b"")
        image = data.startswith((b"\xff\xd8\xff",b"\x89PNG\r\n\x1a\n",
                                b"RIFF",b"GIF87a",b"GIF89a"))
        report["Kiutaku actual original"] = dict(meta,
            valid_image_signature=image,
            url_host=urllib.parse.urlsplit(candidate).hostname or "")
    else:
        report["Kiutaku actual original"] = dict(
            blocked="Gallery page did not expose a mitaku.net original photo URL")

    # A provider HTML page can expose iframe/player metadata without exposing
    # a public downloadable file; do NOT invent a media link or fetch ads.
    jav = report.get("Jav Guru detail", {})
    raw_players = jav.get("_players", [])
    report["Jav Guru player"] = dict(player_url_candidates=len(raw_players),
        player_hosts=list(dict.fromkeys(
            (urllib.parse.urlsplit(absolute(TARGETS["Jav Guru detail"],x)).hostname or "")
            for x in raw_players if absolute(TARGETS["Jav Guru detail"],x)))[:6],
        site_detail_http=jav.get("http",0),
        status=("player_url_observed" if raw_players else
            "rendered_stream_choices_but_no_direct_media_url"))
    # Render actual browser DOM only where static HTTP did not expose cards.
    for name in ("Jav Guru detail", "Archivebate", "Rule34Video"):
        value = report.get(name,{})
        if value.get("http") not in (200,201) or value.get("first_party_links",0)<3:
            browser=render_dom(TARGETS[name])
            dom=browser.pop("dom","")
            if dom:
                parsed=Parse();parsed.feed(dom)
                browser.update(dom_images=len(parsed.images),
                    dom_first_party_links=sum(bool(absolute(TARGETS[name],x)) and
                        first_party(urllib.parse.urlsplit(TARGETS[name]).hostname or "",
                                    absolute(TARGETS[name],x))
                        for x in parsed.anchors))
            report[name+" browser"]=browser

    # First-party actual Rule34Video preview image: a bounded GET, not an
    # indiscriminate scene/video download. Many sites use JS-only cards.
    r34=report.get("Rule34Video", {})
    img = next((absolute(TARGETS["Rule34Video"],x)
        for x in r34.get("_images",[])
        if absolute(TARGETS["Rule34Video"],x)
        and (urllib.parse.urlsplit(absolute(TARGETS["Rule34Video"],x)).hostname or "")
            .endswith("rule34video.com")
        and re.search(r"\.(jpe?g|png|webp)(?:\?|$)",x,re.I)
        and not re.search(r"icon|logo|avatar|banner|ads?[/_-]",x,re.I)),"")
    if img:
        answer=fetch(img,limit=128_000,range_header="bytes=0-127999",
            referer=TARGETS["Rule34Video"])
        meta,data=answer if isinstance(answer,tuple) else (answer,b"")
        report["Rule34Video real thumbnail"] = dict(meta,
            valid_image_signature=data.startswith((b"\xff\xd8\xff",b"\x89PNG",
                                                    b"RIFF",b"GIF8")))
    else:
        report["Rule34Video real thumbnail"] = dict(
            blocked="No actual thumbnail candidate in HTTP HTML; requires rendered first-party cards")

    # A real Rule34Video video detail page may have poster/media URLs absent
    # from the home cards. Fetch an actual first-party watch page as well.
    real_link=next((absolute(TARGETS["Rule34Video"],x)
        for x in r34.get("_anchors",[])
        if re.search(r"(?i)/(?:video|videos)/",x)
        and first_party("rule34video.com",absolute(TARGETS["Rule34Video"],x))),"")
    if real_link:
        answer=fetch(real_link,limit=320_000,referer=TARGETS["Rule34Video"])
        meta,body=answer if isinstance(answer,tuple) else (answer,b"")
        parsed=Parse();parsed.feed(body.decode("utf8","replace"))
        report["Rule34Video actual detail"]=dict(meta,
            video_player_elements=len(parsed.players),
            poster_candidate_count=len(parsed.images),
            path=urllib.parse.urlsplit(real_link).path)
    else:
        report["Rule34Video actual detail"]=dict(
            blocked="No public first-party video route in homepage HTML")

    archive=report.get("Archivebate",{})
    paths=[urllib.parse.urlsplit(absolute(TARGETS["Archivebate"],x)).path
        for x in archive.get("_anchors",[])
        if re.fullmatch(r"/watch/[0-9]+/?",
            urllib.parse.urlsplit(absolute(TARGETS["Archivebate"],x)).path)]
    report["Archivebate video cards"]=dict(valid_watch_links=len(paths),
        gated=archive.get("age_or_gate",False))

    # Publisher chapters are fetched, not assumed from home-page source HTML.
    novel=report.get("FreeWebNovel book", {})
    base=TARGETS["FreeWebNovel book"]
    candidates=list(dict.fromkeys(absolute(base,raw) for raw
        in novel.get("_anchors",[]) if re.search(r"chapter[-/_]?\d+", raw,re.I)))
    candidates=[x for x in candidates if first_party("freewebnovel.com",x)][:3]
    checks=[]
    for url in candidates[:2]:
        answer=fetch(url,limit=180_000,referer=base)
        meta,data=answer if isinstance(answer,tuple) else (answer,b"")
        decoded=data.decode("utf8","replace")
        checks.append(dict(http=meta.get("http",0),bytes=meta.get("bytes",0),
            has_reader=bool(re.search(r'id=["\']article|class=["\']chapter-content|'
                                   r'id=["\']chapter-content',decoded,re.I)),
            numbered_title=bool(re.search(r"chapter\s+\d+",decoded,re.I)),
            path=urllib.parse.urlsplit(url).path))
    report["FreeWebNovel actual chapters"] = dict(
        chapter_links_found=len(candidates),checked=checks,
        limitation=("No real chapter links in returned listing HTML"
            if not candidates else ""))

    # Remove raw HTML/URLs and content from the artifact. Exact original files
    # and login cookies are never kept in CI.
    for item in report.values():
        for key in ("_page","_images","_anchors","_players"):
            item.pop(key,None)
    with open(OUT,"w",encoding="utf8") as stream:
        json.dump(report,stream,ensure_ascii=False,indent=2)
    for name,result in report.items():
        print(name+": HTTP "+str(result.get("http","?"))+
              "; cards "+str(result.get("first_party_links","?"))+
              "; images "+str(result.get("images","?"))+
              "; "+str(result.get("error") or result.get("blocked") or ""))
    print("EVIDENCE",OUT)

if __name__=="__main__":
    main()
