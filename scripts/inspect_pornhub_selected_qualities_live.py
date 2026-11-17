#!/usr/bin/env python3
"""Publisher-selected Pornhub HLS quality accessibility without downloading media.

Reads ONE real homepage-linked watch with a unique flashvars config, then
checks only that same watch's approved HLS manifests. No IP/access bypass,
recommendations, credentials, or signed URL printed.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from html.parser import HTMLParser
from verify_pornhub_full_selected_pc import UA,HOME,Links,get
def main():
    page=Links();page.feed(get(HOME,HOME))
    watches=[]
    for href in page.links:
        url=urllib.parse.urlsplit(urllib.parse.urljoin(HOME,html.unescape(href)))
        qs=urllib.parse.parse_qs(url.query)
        if(url.scheme=="https" and url.hostname in ("www.pornhub.com","pornhub.com") and
           url.path=="/view_video.php" and len(qs.get("viewkey",[]))==1):
            watches.append(url.geturl())
    if not watches:raise RuntimeError("No publisher-selected watch page")
    selected=watches[0]
    raw=get(selected,HOME)
    configs=[]
    for m in re.finditer(r"\b(?:var\s+)?flashvars_[A-Za-z0-9_]{1,72}\s*=\s*",raw):
        try:obj,_=json.JSONDecoder().raw_decode(raw[m.end():].lstrip())
        except ValueError:continue
        if isinstance(obj,dict) and isinstance(obj.get("mediaDefinitions"),list):
            configs.append(obj)
    if len(configs)!=1:raise RuntimeError("Ambiguous selected flashvars")
    links=[]
    for item in configs[0]["mediaDefinitions"]:
        if not isinstance(item,dict) or item.get("format") not in ("hls","hls_master"):
            continue
        uri=urllib.parse.urlsplit(str(item.get("videoUrl") or "").replace("\\/","/"))
        if(uri.scheme=="https" and uri.hostname in ("hm-h.phncdn.com","em-h.phncdn.com") and
           uri.path.endswith(".m3u8") and uri.query and not uri.username):
            links.append((str(item.get("quality","")),uri.geturl()))
    seen=set()
    for quality,url in links:
        if url in seen:continue
        seen.add(url)
        req=urllib.request.Request(url,headers={
            "User-Agent":UA,"Referer":selected,"Accept-Encoding":"identity"})
        try:
            with urllib.request.urlopen(req,timeout=22) as response:
                buf=response.read(60000)
                result={"http":response.status,"manifest":buf.lstrip().startswith(b"#EXTM3U"),
                    "manifest_bytes":len(buf)}
        except urllib.error.HTTPError as err:
            result={"http":err.code,"manifest":False}
        except Exception as err:
            result={"error_type":type(err).__name__,"manifest":False}
        print(json.dumps({"stage":"SELECTED_QUALITY_ACCESS",
          "quality":quality,"host":urllib.parse.urlsplit(url).hostname,
          **result}),flush=True)
    print(json.dumps({"stage":"SELECTED_QUALITY_PROBE_DONE",
        "count":len(seen),"complete_originals":0}),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as ex:
        print(json.dumps({"status":"SELECTED_QUALITY_UNVERIFIED",
            "error_type":type(ex).__name__,"message":str(ex)[:120]}),flush=True)
        raise SystemExit(2)
