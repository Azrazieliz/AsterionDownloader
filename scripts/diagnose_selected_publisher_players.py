#!/usr/bin/env python3
"""Determine actual publisher-selected player contracts, static first-party only.
Do not record media URLs/signature tokens, access cookies or download bytes.
"""
import concurrent.futures,html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
SAMPLES={
"fullporner.com":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
"hqpornerxxx.com":"https://hqpornerxxx.com/videos/keiranlee-less-talking-more-knocking-39904.html",
"roshy.tv":"https://roshy.tv/video/rctd-759-english-subtitles-decensored-rocket-18th-anniversary-user-request-festival-lesbian-rough-fight-extreme/",
"javideo.net":"https://javideo.net/en/fc2ppv-4994603",
"archivebate.com":"https://archivebate.com/watch/16458050",
"mp4hentai.com":"https://mp4hentai.com/2026/11/jane-doe-animation-release/",
"hentaibros.net":"https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
"3dhentaix.com":"https://3dhentaix.com/ntr-compilation/",
"hentaihaven.xxx":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
"uncensoredhentai.xxx":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
"rule34video.com":"https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/",
"notfans.com":"https://notfans.com/",
}
class Tags(HTMLParser):
    def __init__(self):
        super().__init__();self.element=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag in ("iframe","video","source","a","meta"):
            self.element.append((tag,a))
def safeuri(base,raw):
    if not raw:return None
    u=urllib.parse.urlsplit(urllib.parse.urljoin(base,html.unescape(raw)))
    if u.scheme!="https" or not u.hostname:return None
    return {"host":u.hostname,"extension":u.path.rsplit(".",1)[-1][:8]
              if "." in u.path.rsplit("/",1)[-1] else "",
            "path_type":"/player/" if "/player/" in u.path
               else "/embed/" if "/embed/" in u.path
               else "/download/" if "/download/" in u.path
               else "/e/" if u.path.startswith("/e/") else
               "/v/" if u.path.startswith("/v/") else "other"}
def probe(entry):
    host,url=entry; result={"host":host}
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity","Accept":"text/html"})
    try:
        with urllib.request.urlopen(req,timeout=16) as r:
            result["status"]=r.status;final=r.url
            source=r.read(1_500_000).decode("utf8","replace")
        d=Tags();d.feed(source)
        vals=[]
        for tag,a in d.element:
            if (tag in ("iframe","video","source") or
                (tag=="a" and ("download" in a or
                  "/get_file/" in a.get("href","") or "/download/" in a.get("href",""))) or
                (tag=="meta" and (a.get("itemprop")=="contentUrl" or
                  a.get("property","").lower().startswith("og:video")))):
                v=safeuri(final,a.get("src") or a.get("href") or a.get("content"))
                if v:vals.append({"tag":tag,**v,"classes":a.get("class","")[:80],"id":a.get("id","")[:60]})
        result["elements"]=vals[:12]
        result["source_counts"]={}
        for name,expr in {
            "mp4_text":r"\\.mp4","hls_text":r"\\.m3u8",
            "dash_text":r"\\.mpd",
            "direct_sources":r"(?i)video_url|videoUrl|file\\s*[:=]|sources\\s*[:=]",
            "wp_embeds":r"(?i)wp-block-embed|embed-player|player-iframe",
            "js_player":r"(?i)videojs\\(|jwplayer\\(|plyr\\(|videojs|fluidPlayer",
            "clip_notice":r"(?i)preview|sample|teaser",
            "access_gate":r"(?i)just a moment|verify you are human|access denied|age verification"
        }.items():result["source_counts"][name]=len(re.findall(expr,source))
        result["download_anchors"]=sum(1 for tag,a in d.element if tag=="a" and ("download" in a or "download" in a.get("href","")))
        result["iframe_count"]=sum(1 for tag,_ in d.element if tag=="iframe")
        result["video_count"]=sum(1 for tag,_ in d.element if tag=="video")
    except urllib.error.HTTPError as e:result["status"]=e.code
    except Exception as e:result["error"]=type(e).__name__+":"+str(e)[:80]
    return result
if __name__=="__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        rows=list(pool.map(probe,SAMPLES.items()))
    for row in rows:print(json.dumps(row),flush=True)
