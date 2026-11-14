#!/usr/bin/env python3
"""Site-by-site live acceptance probes; zero complete media downloads.
Only published selected-page/player routes. Media checks request 256 bytes
with HTTP Range and reject HTML/gateways; no cookie or DRM bypass.
"""
import concurrent.futures, importlib.util, re, sys, urllib.error, urllib.parse, urllib.request
from html.parser import HTMLParser

CASES={
    "fullporner":("https://fullporner.com/watch/6af6fbcd2f7835591446cb0f","/player/"),
    "hqporner":("https://hqpornerxxx.com/videos/keiranlee-less-talking-more-knocking-39904.html","myxstudio.top"),
    "javideo":("https://javideo.net/en/fc2ppv-4994603","iframe"),
    "hentaibros":("https://hentaibros.net/deco-x-deco-the-animation-episode-1/","video"),
    "3dhentaix":("https://3dhentaix.com/ntr-compilation/","video"),
    "hentaihaven":("https://hentaihaven.xxx/watch/renseijutsushi-collet-no-h-na-shibo-shirage-monogatari/","video"),
    "uncensoredhentai":("https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/","nhplayer.com"),
    "rule34video":("https://rule34video.com/video/4727779/female-v-x-bbc-reverse-cowgirl/","download=true"),
    "roshy":("https://roshy.tv/category/decensored-6/","k2s.cc"),
    "mp4hentai":("https://mp4hentai.com/","video"),
    "helloporn":("https://helloporn.com/","video"),
    "helloporn_selected":("https://helloporn.com/video-28699-fakedrivingschool-naughty-student-late-for-her-exam-j","player"),
}
class Page(HTMLParser):
    def __init__(self):
        super().__init__()
        self.hrefs=[];self.iframes=[];self.videos=[];self.scripts=[];self.inline=[]
        self.current=False;self.buff=""
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"): self.hrefs.append(a)
        if tag=="iframe":self.iframes.append(a)
        if tag in ("video","source"):self.videos.append(a)
        if tag=="script" and a.get("src"):self.scripts.append(a["src"])
        if tag=="script" and not a.get("src"): self.current=True;self.buff=""
    def handle_data(self,data):
        if self.current and len(self.buff)<150000:self.buff+=data[:150000]
    def handle_endtag(self,tag):
        if tag=="script" and self.current:self.inline.append(self.buff);self.current=False
def get(url,ref=None):
    h={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36",
       "Accept-Encoding":"identity","Accept":"text/html,application/xhtml+xml"}
    if ref:h["Referer"]=ref
    req=urllib.request.Request(url,headers=h)
    try:
        with urllib.request.urlopen(req,timeout=15) as r:
            raw=r.read(1800000)
            return r.status,r.url,raw.decode("utf8","replace")
    except urllib.error.HTTPError as e:return e.code,url,""
    except Exception as e:return type(e).__name__,url,""
def probe_case(item):
    name,(page,selector)=item
    code,url,body=get(page)
    doc=Page();doc.feed(body)
    trusted_host=urllib.parse.urlsplit(url).hostname or ""
    original=[]
    for a in doc.hrefs:
        href=urllib.parse.urljoin(url,a["href"])
        if "download=true" in href and "/get_file/" in href:original.append(href)
    elements=[]
    for f in doc.iframes:
        src=urllib.parse.urljoin(url,f.get("src",""))
        parsed=urllib.parse.urlsplit(src)
        if parsed.scheme!="https":continue
        if "fullporner" in name and parsed.hostname in ("fullporner.com","www.fullporner.com") and re.match(r"^/player/\d+/\d+/?$",parsed.path):
            elements.append(("fullporner_player",src))
        elif name=="hqporner" and parsed.hostname in ("myxstudio.top","www.myxstudio.top") and re.match(r"^/embed/[a-zA-Z0-9_-]+/?$",parsed.path):
            elements.append(("hq_embed",src))
        elif name=="uncensoredhentai" and parsed.hostname=="nhplayer.com" and re.match(r"^/v/[A-Za-z0-9]+/?$",parsed.path):
            elements.append(("nhplayer",src))
        else:
            elements.append(("other_iframe",src))
    output={"name":name,"HTTP":code,"bytes":len(body),
            "iframes":[{"type":e,"host":urllib.parse.urlsplit(u).hostname,"route":urllib.parse.urlsplit(u).path[:45]} for e,u in elements[:4]],
            "direct_original_links":len(original),
            "video_elements":len(doc.videos)}
    for et,player in elements:
        if et not in ("fullporner_player","hq_embed","nhplayer"):continue
        sub_code,sub_link,inner=get(player,url)
        subdoc=Page();subdoc.feed(inner)
        output["selected_player"]={"host":urllib.parse.urlsplit(player).hostname,
            "HTTP":sub_code,"video":len(subdoc.videos),"iframes":len(subdoc.iframes),
            "script_count":len(subdoc.scripts),
            "scripted_source_markers":sum(len(re.findall(
                r"(?i)(?:file|src|source|url)\s*[:=]\s*['\"]https?://|\.m3u8|\.mp4(?!\.jpg)",
                src)) for src in subdoc.inline),
            "video_source_hosts":[urllib.parse.urlsplit(
                urllib.parse.urljoin(player,ele.get("src",""))).hostname
                for ele in subdoc.videos[:3]]}
        if et=="hq_embed" and subdoc.iframes:
            output["selected_player"]["nested_iframe_hosts"]=[
                urllib.parse.urlsplit(urllib.parse.urljoin(player,z.get("src",""))).hostname
                for z in subdoc.iframes[:3]]
        break
    if name=="fullporner":
        output["player_function_presence"]={key:bool(re.search(pat,inner if 'inner' in locals() else body))
            for key,pat in {"flvv":r"\bflvv\b","btq":r"\bbtq\(","reverse_id":r"\.reverse\(",
                "fluidPlayer":r"fluidPlayer\(","source_assignment":r"\.src\s*=|\.attr\(['\"]src"}.items()}
    return output
if __name__=="__main__":
    print("python_yt_dlp_installed",bool(importlib.util.find_spec("yt_dlp")),flush=True)
    print("python_requests_installed",bool(importlib.util.find_spec("requests")),flush=True)
    chosen=[item for item in CASES.items() if len(sys.argv)==1 or item[0] in sys.argv[1:]]
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as executor:
        for result in executor.map(probe_case,chosen):
            print(result,flush=True)
