#!/usr/bin/env python3
"""Read only duration evidence from one publisher-owned HTTPS media page."""
import argparse,re,urllib.request,urllib.parse,html as htmlmod
p=argparse.ArgumentParser()
p.add_argument("--url",required=True)
o=p.parse_args()
if urllib.parse.urlsplit(o.url).scheme!="https":raise SystemExit("HTTPS_REQUIRED")
req=urllib.request.Request(o.url,headers={"User-Agent":"Mozilla/5.0 (Linux; Android 17; Mobile)"})
with urllib.request.urlopen(req,timeout=19) as rsp:
    doc=rsp.read(2_500_000).decode("utf8","replace")
    print("PAGE_HOST",urllib.parse.urlsplit(rsp.geturl()).hostname)
for prop in ["itemprop","name","property"]:
    for m in re.finditer(r"<meta\b[^>]*?>",doc,re.I):
        t=m.group(0)
        if not re.search(r'\b'+prop+r'\s*=\s*["\'](?:video:)?duration["\']',t,re.I):
            continue
        val=re.search(r'\bcontent\s*=\s*["\']([^"\']+)["\']',t,re.I)
        if val:print("META_DURATION",htmlmod.unescape(val.group(1))[:40])
for m in re.finditer(r'["\']duration["\']\s*:\s*["\'](PT[^"\']+)["\']',doc,re.I):
    print("JSON_DURATION",htmlmod.unescape(m.group(1))[:40])
for m in re.finditer(r'\b(?:videoDuration|duration)\s*[:=]\s*["\']?(\d{2,6})["\']?',doc,re.I):
    print("SCRIPT_DURATION",m.group(1))
