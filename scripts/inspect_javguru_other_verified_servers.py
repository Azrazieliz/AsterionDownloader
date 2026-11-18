#!/usr/bin/env python3
"""Probe only the owner's other publisher-selected JavGuru STREAM DD/JK originals.

Exact MXGS-1452 post -> one official shortcode control -> first-party
/searcho/ -> declared final hoster. Inspect only its own video element and
packed player file assignment, not ads/related videos or arbitrary scripts.
No full-file success claim and no signed URLs printed.
"""
import base64,html,json,re,sys,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from verify_javguru_exact_selected_hls import Doc,get,value,UA
from verify_roshy_selected_packed_hls import Script as PackerScript,deobfuscate
from inspect_javguru_owner_real_streams import WATCH
class Videos(HTMLParser):
    def __init__(self):
        super().__init__();self.videos=[]
    def handle_starttag(self,tag,attrs):
        if tag=="video":
            a=dict(attrs)
            self.videos.append({"id":a.get("id","")[:45],"class":a.get("class","")[:90],
                "has_src":bool(a.get("src")),"preload":a.get("preload","")[:20],
                "autoplay":"autoplay" in a,"controls":"controls" in a,
                "poster_present":bool(a.get("poster"))})
def go(label):
    http,final,page=get(WATCH,WATCH)
    if http!=200 or urllib.parse.urlsplit(final).hostname!="jav.guru":
        raise RuntimeError("exact post not accessible")
    d=Doc();d.feed(page)
    buttons=[x for x in d.buttons if x["label"].strip().upper()=="STREAM "+label]
    if len(buttons)!=1:raise RuntimeError("selected publisher label not unique")
    key=buttons[0]["key"]
    sources=[]
    for js in d.code:
        for match in re.finditer(r"\bvar\s+([a-z]{10})\s*=\s*(\{[^}]{20,2400}\})",js,re.S):
            if match.group(1)!=key:continue
            obj=json.loads(match.group(2))
            raw=base64.b64decode(obj["iframe_url"],validate=True).decode("utf8")
            u=urllib.parse.urlsplit(raw)
            if u.scheme=="https" and u.hostname=="jav.guru" and u.path=="/searcho/":
                sources.append(raw)
    if len(set(sources))!=1:raise RuntimeError("not unique first-party publisher shortcode")
    wrapper=sources[0]
    code,_,markup=get(wrapper,WATCH)
    if code!=200:raise RuntimeError("wrapper not HTTP 200")
    w=Doc();w.feed(markup)
    scripts=[s for s in w.code if "window.cfg" in s and "fullToken.rev()" in s]
    if len(scripts)!=1:raise RuntimeError("first-party wrapper JS not unique")
    js=scripts[0]
    cid=value(js,"cid");base=value(js,"base");rtype=value(js,"rtype")
    m=re.search(r"\bkeys\s*:\s*\[\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\]",js)
    if not cid or cid not in w.nodes or base!="https://jav.guru/searcho/" or not m:
        raise RuntimeError("no publisher-attested selected next hop")
    parts=[w.nodes[cid].get(k) for k in m.groups()]
    if not all(x and re.fullmatch(r"[A-Za-z0-9]{5,55}",x) for x in parts):
        raise RuntimeError("selected first-party token absent")
    url=base+"?"+rtype+"r="+("".join(parts)[::-1])
    code,embed,player=get(url,wrapper,950000)
    host=urllib.parse.urlsplit(embed).hostname
    if code!=200 or (label=="JK" and host!="maxstream.org") or (
            label=="DD" and host!="playmogo.com"):
        raise RuntimeError("exact selected server redirected outside publisher hoster")
    rendered=Videos();rendered.feed(player)
    packed=PackerScript();packed.feed(player)
    candidates=[]
    for raw in packed.raw:
        if "eval(function(p,a,c,k,e,d)" not in raw:continue
        decoded=deobfuscate(raw)
        if not decoded:continue
        for m in re.finditer(
            r"""(?i)\bfile\s*:\s*["'](https?://[^"'<>\\\s]{10,2200})["']""",decoded):
            raw=m.group(1).replace("\\/","/")
            u=urllib.parse.urlsplit(raw)
            if(u.scheme=="https" and u.hostname and
                u.hostname not in ("googletagmanager.com","doubleclick.net") and
                not re.search(r"(?:preview|teaser|sample|trailer)",u.path,re.I)
                and u.path.lower().endswith((".m3u8",".mp4"))):
                candidates.append(raw)
    candidates=list(dict.fromkeys(candidates))
    printed=[{"host":urllib.parse.urlsplit(x).hostname,
        "ext":urllib.parse.urlsplit(x).path.rsplit(".",1)[-1][:8],
        "signed":bool(urllib.parse.urlsplit(x).query)} for x in candidates]
    print(json.dumps({"provider":"jav_guru","selected_server":label,
        "stage":"OFFICIAL_SELECTED_ALTERNATE_SOURCE",
        "final_hoster":host,"html_video_tags":rendered.videos,
        "source_candidate_count":len(candidates),"source_shapes":printed,
        "full_original_verified":False}),flush=True)
    for original in candidates[:2]:
        p=urllib.parse.urlsplit(original)
        headers={"User-Agent":UA,"Referer":embed,"Accept-Encoding":"identity"}
        if p.path.endswith(".mp4"):headers["Range"]="bytes=0-4095"
        req=urllib.request.Request(original,headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=23) as resp:
                buf=resp.read(5000)
                hdr=buf.lstrip().startswith(b"#EXTM3U")
                media=buf[4:8]==b"ftyp"
                print(json.dumps({"selected_server":label,
                    "stage":"FIRST_BYTES_OF_SELECTED_PUBLISHED_SOURCE",
                    "http":resp.status,"host":p.hostname,
                    "media_type":resp.headers.get("Content-Type","").split(";")[0],
                    "hls_manifest":hdr,"mp4_header":media,
                    "sample_bytes":len(buf),"full_original_verified":False}),flush=True)
        except urllib.error.HTTPError as err:
            print(json.dumps({"selected_server":label,
                "stage":"SELECTED_SOURCE_HTTP_BLOCKED","http":err.code}),flush=True)
        except Exception as err:
            print(json.dumps({"selected_server":label,
                "stage":"SELECTED_SOURCE_REQUEST_FAILED",
                "error_type":type(err).__name__}),flush=True)
def main():
    for label in ("DD","JK"):
        try:go(label)
        except Exception as e:
            print(json.dumps({"selected_server":label,"stage":"ALT_SERVER_NOT_RESOLVED",
                "error_type":type(e).__name__}),flush=True)
    return 0
if __name__=="__main__":sys.exit(main())
