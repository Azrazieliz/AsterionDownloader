#!/usr/bin/env python3
"""Read exact selected NHPlayer first-party AJAX/player script structure only.

No challenge bypass, cookie export, token replay, ad capture or media save.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
OWNER="https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class H(HTMLParser):
    def __init__(self):
        super().__init__();self.frames=[];self.servers=[];self.scripts=[];self.active=None
    def handle_starttag(self,t,attrs):
        d=dict(attrs)
        if t=="iframe" and d.get("src"):self.frames.append(d["src"])
        if t=="li" and d.get("data-id"):self.servers.append(d["data-id"])
        if t=="script" and not d.get("src"):self.active=""
    def handle_data(self,t):
        if self.active is not None:self.active+=t
    def handle_endtag(self,t):
        if t=="script" and self.active is not None:
            self.scripts.append(self.active);self.active=None
def get(u,ref):
    with urllib.request.urlopen(urllib.request.Request(u,headers={
        "User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"}),timeout=20) as r:
        return r.read(700000).decode("utf8","replace")
def safe(t):
    t=re.sub(r"https?(?::|\\:)//[^\s\"'<>\\]{8,2500}","[URL]",t)
    t=re.sub(r"[A-Za-z0-9_+=/-]{35,}","[opaque]",t)
    return re.sub(r"\s+"," ",t)[:520]
def main():
    doc=H();doc.feed(get(OWNER,OWNER))
    selected=[]
    for frame in doc.frames:
        v=urllib.parse.urlsplit(urllib.parse.urljoin(OWNER,html.unescape(frame)))
        if v.scheme=="https" and v.hostname=="nhplayer.com" and re.fullmatch(r"/v/[A-Za-z0-9]{8,40}/?",v.path):
            selected.append(v.geturl())
    if len(set(selected))!=1:raise RuntimeError("Selected player not unique")
    nh=H();nh.feed(get(selected[0],OWNER))
    if len(nh.servers)!=1:raise RuntimeError("Selected NHPlayer server not unique")
    target=urllib.parse.urljoin(selected[0],html.unescape(nh.servers[0]))
    if urllib.parse.urlsplit(target).hostname!="nhplayer.com" or urllib.parse.urlsplit(target).path!="/player.php":
        raise RuntimeError("Not selected first party player")
    first=H();first.feed(get(target,selected[0]))
    snippets=[]
    for script in first.scripts:
        for needle in ("AJAX mode","initPlayer(","fetch(","XMLHttpRequest","challenge","fileUrl",
                       "captcha","verified","token","ajax","response","download"):
            matches=list(re.finditer(re.escape(needle),script,re.I))
            if not matches:continue
            m=matches[-1]
            snippets.append({"keyword":needle,"count":len(matches),
                "context":safe(script[max(0,m.start()-130):m.end()+350])})
    print(json.dumps({"stage":"NHPLAYER_SELECTED_AUTHORIZED_AJAX_SHAPE",
        "inline_blocks":len(first.scripts),"selected_server_host":"nhplayer.com",
        "snippets":snippets[:16],"media_verified":False}),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as e:
        print(json.dumps({"status":"NHPLAYER_AJAX_UNVERIFIED",
           "error_type":type(e).__name__,"message":str(e)[:140]}),flush=True)
        raise SystemExit(2)
