#!/usr/bin/env python3
"""Owner-selected public publisher/player DOM structure diagnostic.

One real selected recording per already-integrated site, no media download,
account authentication, arbitrary iframe following, raw URLs, cookies, tokens,
video titles or claims of verified originals.
"""
import json,re,urllib.error,urllib.parse,urllib.request
from html.parser import HTMLParser

UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"

class Elements(HTMLParser):
    def __init__(self):
        super().__init__()
        self.anchors=[];self.frames=[];self.video=[];self.scripts=[]
        self.current=None
    def handle_starttag(self,tag,attrs):
        a=dict(attrs)
        if tag=="a" and a.get("href"):
            self.current={"href":a["href"],"class":a.get("class",""),
                          "id":a.get("id",""),"label":""}
            self.anchors.append(self.current)
        if tag=="iframe" and a.get("src"):self.frames.append(a["src"])
        if tag in ("video","source"):self.video.append(a)
        if tag=="script" and a.get("src"):self.scripts.append(a["src"])
    def handle_data(self,data):
        if self.current and len(self.current["label"])<100:
            self.current["label"]+=data[:100]
    def handle_endtag(self,tag):
        if tag=="a":self.current=None

def fetch(url):
    request=urllib.request.Request(url,headers={
        "User-Agent":UA,"Accept-Encoding":"identity",
        "Accept":"text/html,application/xhtml+xml"})
    with urllib.request.urlopen(request,timeout=20) as response:
        return response.status,response.url,response.read(1_500_000).decode("utf8","replace")

def shape(base,value):
    u=urllib.parse.urlsplit(urllib.parse.urljoin(base,value))
    p=u.path.lower()
    return {"host":u.hostname,"path_kind":
        "embed" if re.search(r"/(?:embed|e|v)/",p) else
        "video" if re.search(r"/(?:video|watch|movie)/",p) else
        "file" if p.endswith((".mp4",".m3u8",".mpd")) else "other",
        "query_keys":sorted(urllib.parse.parse_qs(u.query))[:8]}

def one(site,listing,pattern):
    status,canonical,html=fetch(listing)
    found=Elements();found.feed(html)
    selected=[]
    for item in found.anchors:
        p=urllib.parse.urlsplit(urllib.parse.urljoin(canonical,item["href"]))
        if p.scheme=="https" and p.hostname and p.hostname.removeprefix("www.")==site and re.fullmatch(pattern,p.path,re.I):
            selected.append(p.geturl())
    selected=list(dict.fromkeys(selected))
    if not selected:return {"provider":site,"listing_http":status,"error":"NO_SELECTED_ARTICLE"}
    status,article,html=fetch(selected[0]);doc=Elements();doc.feed(html)
    anchors=[]
    for item in doc.anchors:
        label=" ".join(item["label"].split())[:90]
        if not re.search(r"play|watch|stream|download|video|mirror",label+" "+
            item["id"]+" "+item["class"],re.I):continue
        shaped=shape(article,item["href"])
        if shaped["host"] in ("go.greentrafsa.com",):continue
        anchors.append({"label":label[:45],"class":item["class"][:70],
                        "id":item["id"][:60],**shaped})
    embeds=[shape(article,x) for x in doc.frames][:12]
    return {"provider":site,"selected_http":status,
      "selected_public_html_size":len(html),
      "anchor_candidates":anchors[:25],
      "iframes":embeds,
      "video_elements":[{"has_src":bool(x.get("src")),
         "data_keys":sorted(k for k in x if k.startswith("data-"))[:15]}
         for x in doc.video[:10]],
      "external_script_hosts":sorted(set(shape(article,x)["host"]
         for x in doc.scripts if shape(article,x)["host"]))[:25],
      "json_ld_count":len(re.findall(r'application/ld\\+json',html,re.I)),
      "media_literal_counts":{ext:len(re.findall(re.escape(ext),html,re.I))
         for ext in (".mp4",".m3u8",".mpd")},
      "complete_original_verified":False}

if __name__=="__main__":
    for args in [
        ("onscreens.me","https://www.onscreens.me/",
         r"/[a-f0-9-]{36}/[a-z0-9-]{9,255}/?"),
        ("taiav.com","https://taiav.com/cn/",
         r"/(?:cn|tw|en)/movie/[a-f0-9]{24}/?")
    ]:
        try:print(json.dumps(one(*args)),flush=True)
        except Exception as e:print(json.dumps({
            "provider":args[0],"error":type(e).__name__,
            "complete_original_verified":False}),flush=True)
