#!/usr/bin/env python3
"""Strict publisher-selected NHPlayer server JWPlayer config diagnosis.

Only the one user-selected uncensoredhentai watch and its first declared
NHPlayer /player.php server; no ad iframes, CAPTCHA solving or guessed URLs.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
OWNER="https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class H(HTMLParser):
    def __init__(self):
        super().__init__()
        self.frames=[];self.servers=[];self.code=[];self.active=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
        if tag=="li" and a.get("data-id"):self.servers.append(a["data-id"])
        if tag=="script" and not a.get("src"):self.active=""
    def handle_data(self,data):
        if self.active is not None:self.active+=data
    def handle_endtag(self,tag):
        if tag=="script" and self.active is not None:
            self.code.append(self.active);self.active=None
def get(url,ref):
    r=urllib.request.Request(url,headers={
        "User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(r,timeout=25) as response:
        return response.status,response.read(600000).decode("utf8","replace")
def safe(s):
    s=re.sub(r"https?://[^\s\"'<>\\]{5,2500}","[URL]",s)
    s=re.sub(r"[a-zA-Z0-9/_+=.-]{28,}","[opaque]",s)
    return re.sub(r"\s+"," ",s)[:230]
def main():
    code,page=get(OWNER,OWNER);owner=H();owner.feed(page)
    frames=set()
    for f in owner.frames:
        u=urllib.parse.urlsplit(urllib.parse.urljoin(OWNER,html.unescape(f)))
        if u.scheme=="https" and u.hostname=="nhplayer.com" and re.fullmatch(r"/v/[A-Za-z0-9]{8,40}/?",u.path):
            frames.add(u.geturl())
    if len(frames)!=1:raise RuntimeError("Publisher selected NHPlayer source ambiguous")
    player=next(iter(frames))
    code,landing=get(player,OWNER);nh=H();nh.feed(landing)
    if len(nh.servers)!=1:raise RuntimeError("Selected NHPlayer first server ambiguous")
    first=urllib.parse.urljoin(player,html.unescape(nh.servers[0]))
    u=urllib.parse.urlsplit(first)
    if u.hostname!="nhplayer.com" or u.path!="/player.php":
        raise RuntimeError("Not exact published first server")
    code,body=get(first,player);doc=H();doc.feed(body)
    signals=[]
    links=[]
    for idx,js in enumerate(doc.code):
        for needle in ("jwplayer","sources","file:","file=","playlist","hls","html5","stream"):
            match=re.search(re.escape(needle),js,re.I)
            if match:signals.append({"script_index":idx,"key":needle,
                "context":safe(js[max(0,match.start()-80):match.end()+150])})
        urls=re.findall(r"https?://[^\s\"'<>\\]{12,1100}",js,re.I)
        for v in urls[:16]:
            target=urllib.parse.urlsplit(v)
            links.append({"host":target.hostname,"last_path_segment_kind":
                target.path.rsplit("/",1)[-1].rsplit(".",1)[-1][:12],
                "query_keys":sorted(urllib.parse.parse_qs(target.query))[:9]})
    print(json.dumps({"stage":"SELECTED_NHPLAYER_JWPLAYER_SHAPE",
        "http":code,"bytes":len(body),"script_blocks":len(doc.code),
        "jwplayer_occurrences":body.lower().count("jwplayer"),
        "player_attributes":signals[:22],
        "literal_url_shapes":links[:14],
        "full_original_verified":False}),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as ex:
        print(json.dumps({"status":"SELECTED_NHPLAYER_UNVERIFIED",
            "error_type":type(ex).__name__,"message":str(ex)[:130]}),flush=True)
        raise SystemExit(2)
