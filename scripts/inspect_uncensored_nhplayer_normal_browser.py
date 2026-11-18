#!/usr/bin/env python3
"""Normal isolated browser check of only owner-selected NHPlayer first server.

No challenge bypass, captcha solving, cookie export, injected unlock token,
or unrelated player. Follow exact selected article -> single NHPlayer /v/
-> single first-party /player.php; render it in existing Edge normally and
inspect whether a playable first-party media source was actually published.
"""
import html,json,re,shutil,subprocess,sys,tempfile,urllib.parse
from html.parser import HTMLParser
from pathlib import Path
from inspect_uncensored_nhplayer_ajax_mode import H,get,OWNER
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
class Media(HTMLParser):
    def __init__(self):super().__init__();self.videos=[];self.sources=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="video":self.videos.append(a)
        if tag=="source":self.sources.append(a)
def main():
    if not EDGE.is_file():raise RuntimeError("existing Edge browser missing")
    doc=H();doc.feed(get(OWNER,OWNER))
    players=set()
    for raw in doc.frames:
        uri=urllib.parse.urlsplit(urllib.parse.urljoin(OWNER,html.unescape(raw)))
        if uri.scheme=="https" and uri.hostname=="nhplayer.com" and re.fullmatch(r"/v/[A-Za-z0-9]{8,40}/?",uri.path):
            players.add(uri.geturl())
    if len(players)!=1:raise RuntimeError("owner-selected article has no single player")
    first=next(iter(players))
    page=H();page.feed(get(first,OWNER))
    if len(page.servers)!=1:raise RuntimeError("selected NHPlayer server ambiguous")
    source=urllib.parse.urljoin(first,html.unescape(page.servers[0]))
    uri=urllib.parse.urlsplit(source)
    if uri.scheme!="https" or uri.hostname!="nhplayer.com" or uri.path!="/player.php" or not uri.query:
        raise RuntimeError("not publisher-selected first server")
    with tempfile.TemporaryDirectory(prefix="asterion_selected_nhplayer_live_") as folder:
        args=[str(EDGE),"--headless=new","--disable-gpu","--no-first-run",
          "--no-default-browser-check","--disable-extensions",
          "--virtual-time-budget=32000","--user-data-dir="+folder,
          "--dump-dom",source]
        proc=subprocess.run(args,capture_output=True,timeout=52)
        markup=proc.stdout.decode("utf8","replace")
    parsed=Media();parsed.feed(markup)
    potential=[]
    for entry in parsed.videos+parsed.sources:
        media=(entry.get("src") or entry.get("data-src") or "")
        u=urllib.parse.urlsplit(urllib.parse.urljoin(source,media))
        if media:
            potential.append({"host":u.hostname,
             "ext":u.path.rsplit(".",1)[-1][-12:],
             "scheme":u.scheme,"same_publisher":u.hostname=="nhplayer.com"})
    challenge=bool(re.search(
       r"verify you are human|cf-turnstile|g-recaptcha|h-captcha|file-captcha|captcha",markup,re.I))
    print(json.dumps({"provider":"uncensoredhentai",
      "stage":"EXACT_SELECTED_NHPLAYER_NORMAL_BROWSER",
      "browser_exit":proc.returncode,
      "dom_chars":len(markup),
      "video_tag_count":len(parsed.videos),
      "source_tag_count":len(parsed.sources),
      "media_candidates":potential[:8],
      "ajax_mentions":len(re.findall(r"\bfetch\s*\(|ajax",markup,re.I)),
      "challenge_detected":challenge,
      "hls_literal_count":len(re.findall(r"\.m3u8",markup,re.I)),
      "mp4_literal_count":len(re.findall(r"\.mp4",markup,re.I)),
      "full_file_verified":False}),flush=True)
    return 0 if proc.returncode==0 and len(markup)>500 else 3
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"uncensoredhentai",
          "status":"SELECTED_NHPLAYER_BROWSER_UNVERIFIED",
          "error_class":type(e).__name__}),flush=True)
        sys.exit(4)
