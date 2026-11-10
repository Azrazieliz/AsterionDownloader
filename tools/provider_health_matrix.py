#!/usr/bin/env python3
"""Check each approved site's actual HTTP/redirect state, never infer downloads.

One pass covers 43 canonical sources with six bounded concurrent requests.
No cookies, credentials, proxy, or bypass. A provider that returns HTML is
NOT accepted as a working original-file downloader.
"""
import concurrent.futures
import json
import pathlib
import re
import urllib.error
import urllib.parse
import urllib.request

ROOT=pathlib.Path(__file__).resolve().parents[1]
SOURCE=ROOT/"app/src/main/java/com/azrael/pixivdumpsync/AlohaSources.kt"

def rows():
    source=SOURCE.read_text(encoding="utf-8")
    m=re.search(r'private val sourceRows="""(.*?)"""\.trimIndent\(\)',source,re.S)
    if not m:raise ValueError("Cannot find canonical provider list")
    parsed=[line.strip().split("|") for line in m.group(1).splitlines()
            if line.strip()]
    if len(parsed)!=43 or any(len(row)!=3 for row in parsed):
        raise ValueError("Canonical provider list changed unexpectedly")
    return parsed

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        return None

OPENER=urllib.request.build_opener(NoRedirect)

def probe(row):
    name,url,area=row
    status="NETWORK"
    target=None
    try:
        req=urllib.request.Request(url,headers={
            "User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"})
        try:res=OPENER.open(req,timeout=8)
        except urllib.error.HTTPError as exc:res=exc
        with res:
            status=res.getcode()
            location=res.headers.get("Location")
            if location:target=urllib.parse.urljoin(url,location)
    except (OSError,ValueError,TimeoutError):pass
    return {
        "name":name,"area":area,"host":urllib.parse.urlsplit(url).hostname,
        "http":status,
        "redirect_host":urllib.parse.urlsplit(target).hostname if target else None,
        "redirect_path":urllib.parse.urlsplit(target).path if target else None
    }

def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
        result=list(pool.map(probe,rows()))
    out=ROOT/"build/provider_health_current.json"
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(result,indent=2),encoding="utf-8")
    print("APPROVED_PROVIDERS",len(result))
    for r in result:
        print(r["area"],"|",r["name"],"|",r["http"],"|",
              r["host"],"|",r["redirect_host"] or "",
              r["redirect_path"] or "")
    print("EVIDENCE_FILE",out)

if __name__=="__main__":main()
