#!/usr/bin/env python3
"""Audit actual public first-party page contracts for ALL approved Downloader sites.

No browsing automation, account access, media-byte retrieval or guessed CDN URLs.
Site URL/page/response evidence stays local to the owner's PC. The JSON report
summarizes only status, provider discovery selectors and site-specific affordances.
"""
import concurrent.futures, html, json, pathlib, re, ssl, urllib.error, urllib.parse, urllib.request
from collections import Counter
from html.parser import HTMLParser
from datetime import datetime,timezone

SRC=pathlib.Path(__file__).resolve().parents[1]/"app/src/main/java/com/azrael/pixivdumpsync/AlohaSources.kt"
body=SRC.read_text(encoding="utf8")
catalog=body.split('sourceRows="""',1)[1].split('""".trimIndent()',1)[0]
SITES=[{"name":row.split("|")[0],"url":row.split("|")[1],"area":row.split("|")[2]} for row in catalog.strip().splitlines() if "|" in row]
for name,url,area in [("Cums","https://cums.net/","ADULT"),("OMG","https://omg.porn/","ADULT"),
                      ("Camstreams","https://camstreams.tv/","VIDEOS")]:
    SITES.append({"name":name,"url":url,"area":area})
assert len(SITES)==40
MEDIA_PATTERNS={
"pornhub.com":r"^/view_video\.php\?[^#]*viewkey=",
"eporner.com":r"^/(?:hd-porn|video-)[a-z0-9]",
"hqpornerxxx.com":r"^/(?:hdporn|video|videos)/",
"jav.guru":r"^/[0-9]{4,10}/[a-z0-9][a-z0-9_-]{3,240}/?$",
"javideo.net":r"^/(?:en/)?[a-z0-9]{1,20}-[0-9]{5,9}",
"roshy.tv":r"^/(?:video|videos|watch)/",
"cums.net":r"^/(?:video|watch|v)/",
"watchporn.to":r"^/(?:video|videos)/",
"archivebate.com":r"^/(?:video|videos|watch|recordings|v)/[a-z0-9_-]+",
"xxxi.porn":r"^/(?:videos|watch|v)/",
"helloporn.com":r"^/(?:videos|watch|v)/",
"notfans.com":r"^/(?:videos|watch|posts|v)/",
"fullporner.com":r"^/watch/[a-f0-9]{24}/?$",
"xxxclub.to":r"^/(?:videos|video|watch)/",
"freepornvideos.xxx":r"^/(?:video|videos|watch)/",
"omg.porn":r"^/(?:[a-z]{2}/)?(?:(?:video|videos|watch)/|[0-9]{2,10}/?$)",
"hentaibros.net":r"^/(?:[a-z0-9][a-z0-9_-]{5,180}/?|(?:videos|video|watch)/)",
"mp4hentai.com":r"^/(?:20[0-9]{2}/[0-9]{2}/|(?:video|watch|hentai)/)",
"onlyhentaistuff.com":r"^/(?:videos|video|watch)/",
"uncensoredhentai.xxx":r"^/(?:videos|video|watch)/",
"3dhentaix.com":r"^/(?:[a-z0-9][a-z0-9_-]{2,180}/?|(?:video|watch)/)",
"hentaihaven.xxx":r"^/(?:video|videos|watch)/",
"rule34video.com":r"^/(?:video|videos)/[0-9]",
"camstreams.tv":r"^/(?:video|videos|watch|clip)/",
}
EXCLUDED=r"(?i)(?:^|/)(?:tag|tags|category|categories|search|home|login|register|model|models|actors|about|privacy|contact|page|latest-videos|random-videos)/"
EXACT={
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
class DOM(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.hrefs=[];self.vids=[];self.frame=[];self.metas=[];self.buttons=0;self.scripts=[]
        self._anchor=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):
            self._anchor={"href":a["href"],"attrs":a,"text":""}
            self.hrefs.append(self._anchor)
        if tag in ("video","source"):
            self.vids.append({"attrs":a,"tag":tag})
        if tag=="iframe":
            self.frame.append(a)
        if tag=="meta":self.metas.append(a)
        if tag=="script" and a.get("src"):self.scripts.append(a["src"])
        if tag=="button":self.buttons+=1
    def handle_data(self,text):
        if self._anchor is not None and len(self._anchor["text"])<120:
            self._anchor["text"]+=text[:120]
    def handle_endtag(self,tag):
        if tag=="a":self._anchor=None

def host_of(url):
    try:return (urllib.parse.urlsplit(url).hostname or "").removeprefix("www.")
    except:return ""
def fetch(url,domain):
    out={"status":None,"bytes":0,"server_host":"","error":None}
    req=urllib.request.Request(url,headers={
      "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
      "Accept":"text/html,application/xhtml+xml","Accept-Encoding":"identity"})
    try:
        with urllib.request.urlopen(req,timeout=13) as r:
            out["status"]=r.status;out["server_host"]=host_of(r.url)
            blob=r.read(900000);out["bytes"]=len(blob)
        if out["server_host"]!=domain:out["error"]="cross_domain_redirect";return out,None
        return out,blob.decode("utf8","replace")
    except urllib.error.HTTPError as e:out["status"]=e.code
    except Exception as e:out["error"]=type(e).__name__
    return out,None

def classify(dom,raw,base,domain):
    links=[]
    for a in dom.hrefs:
        u=urllib.parse.urljoin(base,html.unescape(a["href"]))
        if host_of(u)!=domain:continue
        path=urllib.parse.urlsplit(u).path
        if re.search(EXCLUDED,path):continue
        if re.search(MEDIA_PATTERNS.get(domain,r"(?!)"),path+
            ("?"+urllib.parse.urlsplit(u).query if urllib.parse.urlsplit(u).query else ""),re.I):
            if u not in links:links.append(u)
    framehosts=Counter(host_of(urllib.parse.urljoin(base,f.get("src",""))) for f in dom.frame)
    files=sum(1 for a in dom.hrefs if re.search(r"\.(?:mp4|m3u8|mpd|webm|epub|zip|torrent|apk|glb|fbx)(?:[?#]|$)",a["href"],re.I))
    api_hints={k:bool(re.search(pattern,raw,re.I)) for k,pattern in {
        "download_true":r"download=true",
        "data_player":r"video_url|player_data|flashvars|player_config|videojs|html5mediaembed",
        "videoobject":r'["\\]@type["\\]\s*:\s*["\\]VideoObject',
        "xhr_video":r"xhr/video/",
        "chapter":r"/chapter|chapitre",
        "gallery":r"gallery|album|post-media",
        "torrent":r"magnet:|\.torrent",
        "ebook":r"\.epub|download.*epub",
        "challenge":r"just a moment|cloudflare|verify you are human|captcha",
    }.items()}
    return {
        "candidate_selected_count":len(links),
        "selected_path_first":urllib.parse.urlsplit(links[0]).path[:110] if links else None,
        "iframe_hosts":dict(framehosts.most_common(7)),
        "video_tags":len(dom.vids),
        "download_anchors":sum(1 for a in dom.hrefs if "download" in a["attrs"]),
        "download_true_links":sum(1 for a in dom.hrefs if "download=true" in a["href"]),
        "file_anchors":files,
        "api_hints":api_hints,
        "first_selected":links[0] if links else None,
    }
def analyze(site):
    url=site["url"];domain=host_of(url)
    out={k:site[k] for k in ("name","area")}
    out["host"]=domain
    public,page=fetch(url,domain)
    out["catalog"]=public
    if page is None:return out
    dom=DOM();dom.feed(page)
    profile=classify(dom,page,url,domain)
    first=EXACT.get(domain) or profile.pop("first_selected",None)
    out["catalog_profile"]=profile
    if site["area"] not in ("ADULT","HENTAI","VIDEOS"):
        return out
    if not first or domain not in MEDIA_PATTERNS:return out
    selected,body=fetch(first,domain)
    out["selected"]=selected
    out["selected_path"]=urllib.parse.urlsplit(first).path[:120]
    if body is not None:
        doc=DOM();doc.feed(body)
        info=classify(doc,body,first,domain)
        info.pop("first_selected",None)
        out["selected_profile"]=info
    return out

if __name__=="__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=9) as pool:
        results=list(pool.map(analyze,SITES))
    report={"at_utc":datetime.now(timezone.utc).isoformat(),
            "method":"public_first_party_HTML_only_not_download_proof",
            "providers":results,"total":len(results)}
    dest=pathlib.Path("all_provider_contracts.json")
    dest.write_text(json.dumps(report,indent=2),encoding="utf8")
    for s in results:
        a=s.get("catalog",{})
        sel=s.get("selected",{})
        profile=s.get("selected_profile",{})
        print(f"{s['host']:28s} {s['area']:7s} home={a.get('status')} selected={sel.get('status')} "
              f"cards={s.get('catalog_profile',{}).get('candidate_selected_count',0)} "
              f"frames={profile.get('iframe_hosts',{})} "
              f"media={profile.get('video_tags',0)} "
              f"links={profile.get('download_true_links',0)} "
              f"hint={','.join(k for k,v in profile.get('api_hints',{}).items() if v)}",
              flush=True)
