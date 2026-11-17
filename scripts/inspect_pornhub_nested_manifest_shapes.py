#!/usr/bin/env python3
"""Exact publisher-selected Pornhub master->variant source shape and HTTP status.

Read-only legitimate GET of HLS manifests, no media fragments, signed URLs,
cookies, tokens, access bypass, related videos or account mutation.
"""
import html,json,re,urllib.parse,urllib.request,urllib.error
from verify_pornhub_full_selected_pc import HOME,UA,Links,get
def status(url,ref):
    request=urllib.request.Request(url,headers={
        "User-Agent":UA,"Referer":ref,"Accept-Encoding":"identity"})
    try:
        with urllib.request.urlopen(request,timeout=22) as response:
            return response.status,response.read(100000).decode("utf8","replace")
    except urllib.error.HTTPError as e:
        return e.code,""
def main():
    catalog=Links();catalog.feed(get(HOME,HOME))
    selected=None
    for href in catalog.links:
        u=urllib.parse.urlsplit(urllib.parse.urljoin(HOME,html.unescape(href)))
        if u.scheme=="https" and u.hostname in ("www.pornhub.com","pornhub.com") and u.path=="/view_video.php" and urllib.parse.parse_qs(u.query).get("viewkey"):
            selected=u.geturl();break
    if not selected:raise RuntimeError("no exact selected watch")
    raw=get(selected,HOME)
    configs=[]
    for m in re.finditer(r"\b(?:var\s+)?flashvars_[A-Za-z0-9_]{1,72}\s*=\s*",raw):
        try:obj,_=json.JSONDecoder().raw_decode(raw[m.end():].lstrip())
        except ValueError:continue
        if isinstance(obj,dict) and isinstance(obj.get("mediaDefinitions"),list):
            configs.append(obj)
    if len(configs)!=1:raise RuntimeError("selected player config not unique")
    for obj in configs[0]["mediaDefinitions"]:
        if not isinstance(obj,dict) or obj.get("format") not in ("hls","hls_master"):continue
        quality=str(obj.get("quality",""))
        if quality not in ("240","1080"):continue
        url=str(obj.get("videoUrl","")).replace("\\/","/")
        p=urllib.parse.urlsplit(url)
        if p.scheme!="https" or p.hostname not in ("em-h.phncdn.com","hm-h.phncdn.com"):
            continue
        code,content=status(url,selected)
        variants=[l.strip() for l in content.splitlines() if l.strip() and not l.startswith("#")][:8]
        print(json.dumps({"stage":"SELECTED_MASTER_SHAPE","quality":quality,
            "master_http":code,"master_host":p.hostname,
            "master_query_keys":sorted(urllib.parse.parse_qs(p.query)),
            "advertised_variants":len(variants),"has_m3u_header":content.startswith("#EXTM3U")}),flush=True)
        for item in variants[:2]:
            target=urllib.parse.urljoin(url,item)
            u=urllib.parse.urlsplit(target)
            code,body=status(target,selected)
            print(json.dumps({"stage":"SELECTED_NESTED_MANIFEST_HTTP",
                "quality":quality,"source_relative":not item.startswith("http"),
                "nested_host":u.hostname,"nested_extension":u.path.rsplit(".",1)[-1][:10],
                "nested_query_keys":sorted(urllib.parse.parse_qs(u.query)),
                "nested_http":code,"has_m3u_header":body.startswith("#EXTM3U"),
                "segment_line_count":sum(bool(x) and not x.startswith("#") for x in body.splitlines())}),flush=True)
if __name__=="__main__":
    try:main()
    except Exception as e:
        print(json.dumps({"status":"SELECTED_NESTED_UNVERIFIED",
          "error_type":type(e).__name__,"message":str(e)[:130]}),flush=True)
        raise SystemExit(2)
