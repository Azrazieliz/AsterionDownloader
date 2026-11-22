#!/usr/bin/env python3
"""PC evidence from one real PornAVHD public selected article discovered from
its own front page. No source guesses, media downloads, credentials or bypass.
If the full player is gated, report the actual failure, not a complete file.
"""
import html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
ROOT="https://pornavhd.com/"
ARTICLE=re.compile(r"^/20[0-9]{2}/(?:0?[1-9]|1[0-2])/(?:0?[1-9]|[12][0-9]|3[01])/[a-z0-9][a-z0-9_-]{2,220}/?$",re.I)
EMBED=re.compile(r"^/e/[A-Za-z0-9_-]{8,100}/?$")
class Document(HTMLParser):
    def __init__(self):super().__init__();self.links=[];self.frames=[];self.stack=[];self.videos=[];self.scripts=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):self.links.append(a["href"])
        if tag=="iframe" and a.get("src"):
            self.frames.append({"src":a["src"],"scope":" ".join(self.stack[-6:])[:210]})
        if tag in ("video","source") and a.get("src"):
            self.videos.append({"src":a["src"],"scope":" ".join(self.stack[-6:])[:210]})
        if tag=="script" and a.get("src"):self.scripts.append(a["src"])
        if tag not in ("meta","img","input","link","br","hr","source"):
            self.stack.append(tag+"#"+a.get("id","")+"."+a.get("class",""))
    def handle_endtag(self,tag):
        for j in range(len(self.stack)-1,-1,-1):
            if self.stack[j].split("#")[0]==tag:
                del self.stack[j:];break
def fetch(url,referer):
    req=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"})
    try:
        with urllib.request.urlopen(req,timeout=24) as res:
            body=res.read(1_100_000)
            return res.status,res.url,body.decode("utf8","replace")
    except urllib.error.HTTPError as e:
        return e.code,e.url,e.read(12000).decode("utf8","replace")

def log(stage,**fields):
    print(json.dumps({"provider":"pornavhd","stage":stage,
        "full_selected_original_verified":False,**fields}),flush=True)

def main():
    status,base,markup=fetch(ROOT,ROOT)
    if status!=200:log("HOMEPAGE_BLOCKED",http=status);return 2
    root=Document();root.feed(markup)
    articles=[]
    for raw in root.links:
        parsed=urllib.parse.urlsplit(urllib.parse.urljoin(base,html.unescape(raw)))
        if parsed.scheme=="https" and parsed.hostname in ("pornavhd.com","www.pornavhd.com") and ARTICLE.fullmatch(parsed.path):
            articles.append(urllib.parse.urlunsplit((parsed.scheme,parsed.netloc,parsed.path,"","")))
    selected=list(dict.fromkeys(articles))
    log("PUBLISHED_CATALOG",http=status,html_bytes=len(markup),selected_articles=len(selected))
    if not selected:return 3
    # Pick first genuine published article, never a guessed ID or ad iframe.
    watch=selected[0]
    status,land,page=fetch(watch,ROOT)
    article=Document();article.feed(page)
    frames=[]
    for item in article.frames:
        url=urllib.parse.urlsplit(urllib.parse.urljoin(watch,html.unescape(item["src"])))
        if url.scheme=="https" and url.hostname=="video.playerrecord.cfd" and EMBED.fullmatch(url.path) and not url.query and not url.fragment and ("main#" in item["scope"] or "article#" in item["scope"]):
            frames.append(url.geturl())
    frames=list(dict.fromkeys(frames))
    log("PUBLISHED_SELECTED_ARTICLE",http=status,
        article_date_path="/".join(urllib.parse.urlsplit(watch).path.split("/")[:4]),
        html_bytes=len(page),frames_total=len(article.frames),
        selected_playerrecord_frames=len(frames),
        inline_video_count=len(article.videos),
        publisher_media_host="video.playerrecord.cfd" if frames else None)
    if status!=200 or len(frames)!=1:return 4
    frame=frames[0]
    status,land,player=fetch(frame,watch)
    doc=Document();doc.feed(player)
    kinds=[]
    for item in doc.videos:
        url=urllib.parse.urlsplit(urllib.parse.urljoin(frame,html.unescape(item["src"])))
        kinds.append({"host":url.hostname,
            "extension":url.path.rsplit(".",1)[-1][:8] if "." in url.path else None,
            "has_query":bool(url.query)})
    log("SELECTED_RECORDING_PLAYER",http=status,
        final_host=urllib.parse.urlsplit(land).hostname,
        html_bytes=len(player),video_sources=kinds[:8],
        script_count=len(doc.scripts),
        jwplayer_mentions=len(re.findall("jwplayer",player,re.I)),
        hls_mentions=len(re.findall(r"\.m3u8",player,re.I)),
        mp4_mentions=len(re.findall(r"\.mp4",player,re.I)),
        access_gate=bool(re.search("captcha|verify you are human|turnstile",player[:20000],re.I)))
    return 0 if status==200 else 5
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as error:
        log("PROBE_NOT_VERIFIED",error=type(error).__name__+":"+str(error)[:120])
        sys.exit(9)
