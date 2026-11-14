#!/usr/bin/env python3
"""Public selected-publisher source diagnostics for user-reported broken sites.
No media downloads, no DRM circumvention, no arbitrary browse/media interception.
Outputs only resource host/path shape, player DOM element type and route.
"""
import concurrent.futures,html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser

SITES={
"helloporn.com":["https://helloporn.com/"],
"hqpornerxxx.com":["https://hqpornerxxx.com/"],
"roshy.tv":["https://roshy.tv/category/decensored-6/"],
"javideo.net":["https://javideo.net/en/fc2ppv-4994603"],
"fullporner.com":["https://fullporner.com/watch/6af6fbcd2f7835591446cb0f"],
"uncensoredhentai.xxx":["https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/"],
"mp4hentai.com":["https://mp4hentai.com/"],
"hentaihaven.xxx":["https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/"],
"3dhentaix.com":["https://3dhentaix.com/ntr-compilation/"],
"hentaibros.net":["https://hentaibros.net/deco-x-deco-the-animation-episode-1/"],
"rule34video.com":["https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/"],
"xxxclub.to":["https://xxxclub.to/torrents/details/480815"],
"pornhub.com":["https://www.pornhub.com/"],
}
class Parser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True);self.anchors=[];self.videos=[];self.frames=[];self.images=0;self.jsonld=[];self.stack=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):
            self.anchors.append(a)
        if tag in ("video","source"):
            self.videos.append(a)
        if tag=="iframe":self.frames.append(a)
        if tag=="img":self.images+=1
    def handle_endtag(self,tag):pass
def fetch(url):
    req=urllib.request.Request(url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36","Accept-Encoding":"identity"})
    try:
        with urllib.request.urlopen(req,timeout=17) as r:
            return r.status,r.url,r.read(1200000).decode("utf8","replace")
    except urllib.error.HTTPError as e:return e.code,url,""
    except Exception as e:return type(e).__name__,url,""
def shape(url):
    u=urllib.parse.urlsplit(url)
    return (u.hostname or "")+re.sub(r"/[a-f0-9]{20,}(?=[/?]|$)","/{hash}",re.sub(r"/[0-9]{3,}(?=[/?]|$)","/{id}",u.path))[:110]
def run(data):
    host,urls=data
    results=[]
    for selected in urls:
        status,final,raw=fetch(selected)
        parser=Parser();parser.feed(raw)
        clean=urllib.parse.urlsplit(final)
        anchors=[]
        for a in parser.anchors:
            uri=urllib.parse.urlsplit(urllib.parse.urljoin(final,a["href"]))
            if uri.scheme!="https" or uri.hostname is None or not (uri.hostname==host or uri.hostname=="www."+host):continue
            path=uri.path
            if path in ("","/") or len(path)>170:continue
            anchors.append(path)
        samples=[p for p in dict.fromkeys(anchors)
                 if ("/videos/" in p or "/video/" in p or "/watch/" in p or
                     "/player/" in p or "/torrents/" in p or
                     (p.count("/")==2 and len(p.strip("/"))>12))]
        hints={k:len(re.findall(p,raw,re.I)) for k,p in {
          "get_file":r"/get_file/","download_true":r"download=true",
          "torrent_links":r"magnet:\\?xt=|/torrents/download/",
          "mp4":r"\\.mp4(?:[?\\\\'\\\"]|$)","hls":r"\\.m3u8(?:[?\\\\'\\\"]|$)",
          "dash":r"\\.mpd(?:[?\\\\'\\\"]|$)",
          "videojs":r"videojs|jwplayer|player_data|flashvars|video_url|file:\\s*[\\\"]",
          "cloudflare":r"just a moment|verify you are human|cloudflare",
          "age_gate":r"age verification|age gate|verify your age",
          "videoobject":r"VideoObject|contentUrl"}.items()}
        results.append({"status":status,"final_host":clean.hostname,
                        "sample_routes":samples[:9],"videos":len(parser.videos),
                        "video_host":[shape(urllib.parse.urljoin(final,v.get("src",""))) for v in parser.videos[:6]],
                        "iframe_host":[shape(urllib.parse.urljoin(final,v.get("src",""))) for v in parser.frames[:8]],
                        "href_total":len(parser.anchors),"hint_counts":hints})
    return host,results
if __name__=="__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=7) as pool:
        output=dict(pool.map(run,SITES.items()))
    for k,items in output.items():
        for x in items:
            hints=x.get("hint_counts",{})
            print(k,"HTTP",x["status"],
                  "routes",x["sample_routes"][:5],
                  "videos",x["videos"],
                  "iframes",x["iframe_host"][:3],
                  "hints",",".join(key+":"+str(n) for key,n in hints.items() if n),
                  flush=True)
