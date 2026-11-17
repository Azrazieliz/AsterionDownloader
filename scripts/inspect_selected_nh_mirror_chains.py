#!/usr/bin/env python3
"""Deep, non-destructive selected-source diagnostics for NHPlayer and Mirrored.to.

Reads only the user-selected NHPlayer first-party declared player and the
exact MP4Hentai publisher mirror page. Does not solve CAPTCHA, forge tokens,
download media, or treat third-party advertisements as selected sources.
"""
import html,json,re,subprocess,tempfile,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from pathlib import Path
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
NH="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
MIRROR="https://www.mirrored.to/files/DF7298C6/?dl=0"
MP4PARENT="https://mp4hentai.com/2026/11/nagi-kannagi-18min50s-v/"
EDGE=Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe")
class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.servers=[];self.anchors=[];self._a=None
    def handle_starttag(self,t,attrs):
        a=dict(attrs)
        if t=="li" and a.get("data-id"):self.servers.append(a["data-id"])
        if t=="a" and a.get("href"):
            self._a={"href":a["href"],"text":""}
            self.anchors.append(self._a)
    def handle_data(self,d):
        if self._a and len(self._a["text"])<150:self._a["text"]+=d[:150]
    def handle_endtag(self,t):
        if t=="a":self._a=None

def get(url,ref):
    r=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,
        "Accept-Encoding":"identity"})
    with urllib.request.urlopen(r,timeout=22) as res:
        return res.status,res.url,res.read(1_000_000).decode("utf8","replace")

def nh():
    code,final,body=get(NH,NH);first=Links();first.feed(body)
    assert len(first.servers)==1,"Selected NHPlayer did not publish exactly one server"
    selected=urllib.parse.urljoin(NH,html.unescape(first.servers[0]))
    u=urllib.parse.urlsplit(selected)
    assert u.scheme=="https" and u.hostname=="nhplayer.com" and u.path=="/player.php"
    code,final,markup=get(selected,NH)
    references=[]
    for raw in re.findall(r"""(?i)(?:https?:)?//[A-Za-z0-9._-]+(?:/[^\s"'<>\\]{0,280})?""",markup):
        link=raw if raw.startswith("http") else "https:"+raw
        parsed=urllib.parse.urlsplit(link)
        if parsed.hostname and (
            "htstream" in parsed.hostname or
            parsed.path.endswith((".m3u8",".mp4",".mpd"))):
            references.append({"host":parsed.hostname,
                "ext":parsed.path.rsplit(".",1)[-1][:12],
                "route":"/"+"/".join(
                   token if token in ("e","v","video","embed","player","stream","hls")
                   else "{opaque}" for token in parsed.path.split("/") if token)[:120]})
    print(json.dumps({"provider":"uncensoredhentai","stage":"EXACT_NHPLAYER_JS_REFERENCES",
        "http":code,"selected_player_host":urllib.parse.urlsplit(final).hostname,
        "script_chars":len(markup),
        "jwplayer_mentions":markup.lower().count("jwplayer"),
        "source_mentions":len(re.findall(r"(?:sources?|file)\s*[:=]",markup,re.I)),
        "hls_mentions":markup.lower().count("m3u8"),
        "mp4_mentions":markup.lower().count(".mp4"),
        "declared_hoster_references":references[:12],
        "referer_owned":True}),flush=True)
def mirrored():
    status,final,page=get(MIRROR,MP4PARENT)
    if status!=200:raise RuntimeError("Publisher mirror site not reachable")
    with tempfile.TemporaryDirectory(prefix="asterion_mp4_mirror_safe_") as folder:
        cmd=[str(EDGE),"--headless=new","--disable-gpu","--no-first-run",
            "--no-default-browser-check","--disable-extensions",
            "--virtual-time-budget=18000",
            "--user-data-dir="+folder,"--dump-dom",MIRROR]
        result=subprocess.run(cmd,capture_output=True,timeout=35)
    rendered=result.stdout.decode("utf8","replace")
    anchors=Links();anchors.feed(rendered)
    opts=[]
    for item in anchors.anchors:
        link=urllib.parse.urljoin(MIRROR,html.unescape(item["href"]))
        u=urllib.parse.urlsplit(link)
        keys=urllib.parse.parse_qs(u.query)
        if (u.scheme=="https" and u.hostname in ("www.mirrored.to","mirrored.to")
            and u.path.startswith("/getlink/") and
            set(keys)=={"hid","tid"} and
            item["text"].strip().lower()=="download"):
            if link not in opts:opts.append(link)
    print(json.dumps({"provider":"mp4hentai","stage":"EXACT_MIRROR_RENDERED_GETLINK",
        "html_http":status,"edge_exit":result.returncode,
        "rendered_bytes":len(rendered),"selected_download_links":len(opts),
        "query_keys_checked":True}),flush=True)
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self,req,fp,code,msg,headers,newurl):return None
    opener=urllib.request.build_opener(NoRedirect)
    outcomes=[]
    for link in opts[:5]:
        request=urllib.request.Request(link,headers={
            "User-Agent":UA,"Referer":MIRROR,"Accept-Encoding":"identity"})
        try:
            with opener.open(request,timeout=21) as resp:
                mime=resp.headers.get("Content-Type","").split(";")[0]
                outcomes.append({"http":resp.status,"mime":mime,
                    "host":urllib.parse.urlsplit(resp.url).hostname,
                    "media_first_bytes":False})
        except urllib.error.HTTPError as err:
            dest=err.headers.get("Location","")
            u=urllib.parse.urlsplit(urllib.parse.urljoin(link,dest))
            outcomes.append({"http":err.code,"redirect_host":u.hostname,
                "redirect_path_prefix":u.path.split("/")[1][:30] if "/" in u.path else "",
                "https":u.scheme=="https"})
        except Exception as err:
            outcomes.append({"error_type":type(err).__name__})
    print(json.dumps({"provider":"mp4hentai","stage":"PUBLISHER_MIRROR_HOSTER_OUTCOMES",
        "checked":len(outcomes),"results":outcomes,
        "full_original_verified":False}),flush=True)
if __name__=="__main__":
    for fn in (nh,mirrored):
        try:fn()
        except Exception as e:
            print(json.dumps({"diagnostic":fn.__name__,"status":"NOT_VERIFIED",
                "error_type":type(e).__name__,"reason":str(e)[:140]}),flush=True)
