#!/usr/bin/env python3
"""Inspect exact selected UncensoredHentai -> NHPlayer declared player servers.

The publisher watch explicitly selects NHPlayer /v/<id>. That NHPlayer's
own .servers ul li[data-id] list is authoritative for alternate embeds.
Do not select adverts, related articles, or execute obfuscated JavaScript.
No file save or account mutation.
"""
import html,json,re,urllib.parse,urllib.request
from html.parser import HTMLParser
OWNER="https://uncensoredhentai.xxx/watch/so_low-futago-shimai-to-katei-kyoshi-episode-3/"
NH="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
class Doc(HTMLParser):
    def __init__(self):
        super().__init__()
        self.servers=[]
        self.frames=[]
        self.depth=[]
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="li" and a.get("data-id"):
            self.servers.append({"id":a["data-id"],"class":a.get("class","")[:55]})
        if tag=="iframe" and a.get("src"):
            self.frames.append(a["src"])
def get(url,referer):
    req=urllib.request.Request(url,headers={"User-Agent":UA,
        "Referer":referer,"Accept-Encoding":"identity"})
    with urllib.request.urlopen(req,timeout=22) as r:
        return r.status,r.url,r.read(700000).decode("utf8","replace")
def shape(url):
    u=urllib.parse.urlsplit(url)
    return {"scheme":u.scheme,"host":u.hostname,
        "route_segments":[s if s.lower() in
            ("v","e","embed","video","player","watch","stream","live")
            else "{opaque}" for s in u.path.split("/") if s][:5],
        "query_keys":sorted(urllib.parse.parse_qs(u.query)),
        "valid_https":u.scheme=="https" and bool(u.hostname)}
def main():
    code,final,body=get(NH,OWNER)
    d=Doc();d.feed(body)
    print(json.dumps({"stage":"NHPLAYER_AUTHORIZED_SELECTED_SERVERS",
        "http":code,"original_host":urllib.parse.urlsplit(final).hostname,
        "server_count":len(d.servers),"static_frame_count":len(d.frames),
        "options":[{"server_index":i+1,**shape(urllib.parse.urljoin(NH,html.unescape(v["id"]))),
            "active_class":v["class"]} for i,v in enumerate(d.servers[:12])],
        "contains_htstreaming":any("htstreaming" in s["id"] for s in d.servers)}),flush=True)
    return 0 if d.servers else 2
if __name__=="__main__":
    try:raise SystemExit(main())
    except Exception as ex:
        print(json.dumps({"status":"NHPLAYER_SERVERS_NOT_VERIFIED",
            "type":type(ex).__name__,"message":str(ex)[:140]}),flush=True)
        raise SystemExit(3)
