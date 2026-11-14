#!/usr/bin/env python3
"""Public selected-video publisher diagnostics, no playback, login or media download.
Do not confuse successful HTML retrieval, iframes or preview bytes with originals.
"""
import concurrent.futures, html, json, re, urllib.error, urllib.parse, urllib.request
from html.parser import HTMLParser
from datetime import datetime, timezone

SAMPLES={
 "freepornvideos.xxx":"https://www.freepornvideos.xxx/videos/93823084/serena-gomes-a-savage-debut-in-a-gangbang-with-double-anal/",
 "watchporn.to":"https://watchporn.to/video/1782/title",
 "eporner.com":"https://www.eporner.com/video-MSADu4C0K92/",
 "archivebate.com":"https://archivebate.com/watch/16458050",
 "hentaibros.net":"https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
 "3dhentaix.com":"https://3dhentaix.com/ntr-compilation/",
 "fullporner.com":"https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
 "rule34video.com":"https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/",
 "hentaihaven.xxx":"https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/",
 "uncensoredhentai.xxx":"https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/",
 "javideo.net":"https://javideo.net/en/fc2ppv-4994603",
 "jav.guru":"https://jav.guru/1076811/mukc-135-off-pako-creampie-breeding-photo-session-where-we-defile-a-beautiful-pure-idol-whos-obedient-like-a-doll-however-we-please-shitara-yuuhi/",
}
class Tags(HTMLParser):
    def __init__(self):
        super().__init__()
        self.counts={}
        self.frames=[]
        self.videos=[]
        self.schemas=[]
        self.scripts=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs); self.counts[tag]=self.counts.get(tag,0)+1
        if tag=="iframe":
            u=urllib.parse.urlsplit(a.get("src",""))
            self.frames.append({"host":u.hostname or "","path_shape":re.sub(r"[A-Za-z0-9_-]{6,}","ID",u.path)[:90]})
        if tag in ("video","source"):
            u=urllib.parse.urlsplit(a.get("src",""))
            self.videos.append({"host":u.hostname or "","extension":u.path.rsplit(".",1)[-1][:8] if "." in u.path.rsplit("/",1)[-1] else "",
                                "player_class":a.get("class","")[:60]})
        if tag=="script":
            src=a.get("src","")
            if src: self.scripts.append(urllib.parse.urlsplit(src).hostname or "relative")
        if tag=="meta" and (a.get("itemprop") in ("contentUrl","duration") or
                a.get("property","").lower() in ("og:video","og:video:url")):
            self.schemas.append(a.get("itemprop") or a.get("property"))

def sample(pair):
    host,address=pair
    out={"host":host,"selected_page":urllib.parse.urlsplit(address).path,
        "status":None,"html_size":0,"frames":[],"videos":[],
        "schemas":[],"possible_media_hints":{}}
    req=urllib.request.Request(address,headers={
      "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
      "Accept":"text/html,application/xhtml+xml","Accept-Encoding":"identity"})
    try:
        with urllib.request.urlopen(req,timeout=18) as response:
            out["status"]=response.status
            body=response.read(1_000_000)
            out["html_size"]=len(body)
            out["final_host"]=urllib.parse.urlsplit(response.url).hostname
        page=body.decode("utf-8","replace")
        parsed=Tags();parsed.feed(page)
        out.update(frames=parsed.frames[:10],videos=parsed.videos[:6],
            schemas=parsed.schemas[:12],script_hosts=sorted(set(parsed.scripts))[:15],
            page_structure={k:parsed.counts.get(k,0) for k in ("video","source","iframe","script")})
        # No URLs or publisher tokens are stored. Only identities and counts.
        patterns={
            "hls_ext":r"\\.m3u8(?:[?\\\\'\"]|$)",
            "dash_ext":r"\\.mpd(?:[?\\\\'\"]|$)",
            "mp4_ext":r"\\.mp4(?:[?\\\\'\"]|$)",
            "player_data":r"(?i)(?:player_data|video_url|video_sources|mediaSources|videoUrl|flashvars)",
            "structured_video":r"(?i)(?:VideoObject|og:video|contentUrl)",
            "gate":r"(?i)(?:just a moment|verify you are human|cloudflare|access denied)"
        }
        out["possible_media_hints"]={key:len(re.findall(p, page)) for key,p in patterns.items()}
    except urllib.error.HTTPError as e:out["status"]=e.code
    except Exception as e:out["error"]=type(e).__name__+":"+str(e)[:100]
    return out
if __name__=="__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        results=list(pool.map(sample,SAMPLES.items()))
    print(json.dumps({"at_utc":datetime.now(timezone.utc).isoformat(),
                      "type":"static_public_selected_page_only_not_media_receipts",
                      "results":results},indent=2))
