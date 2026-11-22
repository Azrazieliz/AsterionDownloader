#!/usr/bin/env python3
"""Independent GitHub-hosted PC access check for the three still blocked
publisher/player routes. Never reports complete media from HTTP 200 alone.
No authentication bypass or third-party media substitution. Return JSON for
the release audit, including the exact HTTP barrier or network error class.
"""
import json,re,urllib.error,urllib.parse,urllib.request
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
SITES={
 "avple":{"url":"https://avple.tv/video/1000101415221","kind":"public_selected_example"},
 "livecamrips":{"url":"https://livecamrips.to/models","kind":"public_catalog_only"},
 "pornavhd":{"url":"https://pornavhd.com/","kind":"public_home_only"}
}
out=[]
for name,item in SITES.items():
    status=None;content=b"";final=item["url"];problem=""
    req=urllib.request.Request(item["url"],headers={
        "User-Agent":UA,"Accept-Encoding":"identity",
        "Accept":"text/html,application/xhtml+xml"})
    try:
        with urllib.request.urlopen(req,timeout=20) as res:
            status=res.status;final=res.url;content=res.read(650000)
    except urllib.error.HTTPError as ex:
        status=ex.code;final=ex.url;content=ex.read(10000)
    except Exception as ex:
        problem=type(ex).__name__+":"+str(ex)[:100]
    host=urllib.parse.urlsplit(final).hostname
    parsed={"provider":name,"probe":item["kind"],"publisher_http":status,
       "final_host":host,"html_bytes_received":len(content),
       "access_blocked":status in (401,402,403,429) or bool(problem),
       "reason":problem or ("HTTP "+str(status)),
       "complete_selected_original":False}
    if status==200 and host==urllib.parse.urlsplit(item["url"]).hostname:
        body=content.decode("utf8","replace")
        parsed["direct_video_elements"]=len(re.findall(
            r"<(?:video|source)\\b[^>]*\\bsrc\\s*=",body,re.I))
        parsed["iframe_elements"]=len(re.findall(r"<iframe\\b",body,re.I))
    out.append(parsed)
    print(json.dumps(parsed,sort_keys=True),flush=True)
with open("pc-provider-access-results-20261122.json","w",encoding="utf8") as stream:
    json.dump(out,stream,indent=2)
