#!/usr/bin/env python3
"""Read-only check: can the installed extractor version resolve the exact selected
JAV Guru LU -> LuluStream player using its own Python HTTP transport?
Never download video, log signed URLs, bypass a challenge, or guess a scene."""
import base64
import json
import re
import urllib.parse
from verify_javguru_exact_selected_hls import WATCH, UA, Doc, get, value

def selected_lu():
    status, _, article = get(WATCH, WATCH)
    if status != 200:
        raise RuntimeError("publisher denied selected article")
    root=Doc()
    root.feed(article)
    buttons=[b for b in root.buttons if b["label"].strip().upper()=="STREAM LU"]
    if len(buttons)!=1:
        raise RuntimeError("selected article did not publish one LU button")
    key=buttons[0]["key"]
    found=set()
    for script in root.code:
        for match in re.finditer(r"\bvar\s+"+re.escape(key)+r"\s*=\s*(\{[^}]{20,1000}\})",script,re.S):
            try:
                payload=json.loads(match.group(1))
                address=base64.b64decode(payload["iframe_url"],validate=True).decode()
                uri=urllib.parse.urlsplit(address)
                if uri.scheme=="https" and uri.hostname=="jav.guru" and uri.path=="/searcho/" and uri.query.startswith("ed="):
                    found.add(address)
            except (ValueError,KeyError,UnicodeDecodeError):
                pass
    if len(found)!=1:
        raise RuntimeError("selected LU relay ambiguous")
    relay=found.pop()
    status,_,page=get(relay,WATCH)
    if status!=200:
        raise RuntimeError("selected first-party relay denied")
    doc=Doc();doc.feed(page)
    scripts=[s for s in doc.code if "window.cfg" in s and "fullToken.rev()" in s]
    if len(scripts)!=1:
        raise RuntimeError("selected first-party relay script ambiguous")
    js=scripts[0]
    cid=value(js,"cid");base=value(js,"base");mode=value(js,"rtype")
    keys=re.search(r"\bkeys\s*:\s*\[\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\]",js)
    if cid not in doc.nodes or not keys or base!="https://jav.guru/searcho/" or mode not in ("e","v","s"):
        raise RuntimeError("invalid selected relay")
    parts=[doc.nodes[cid].get(k) for k in keys.groups()]
    if not all(v and re.fullmatch("[A-Za-z0-9]{5,55}",v) for v in parts):
        raise RuntimeError("invalid selected player relay")
    target=base+"?"+mode+"r="+"".join(parts)[::-1]
    status,final,_=get(target,relay)
    u=urllib.parse.urlsplit(final)
    if status!=200 or u.hostname not in ("streamhihi.com","lulustream.com") or not re.fullmatch(r"/e/[A-Za-z0-9_-]{5,180}/?",u.path):
        raise RuntimeError("selected LU did not redirect to its publisher-attested hoster")
    return target,relay,final

def main():
    import yt_dlp
    from yt_dlp.version import __version__
    print(json.dumps({"stage":"SELECTED_LU_PYTHON_EXTRACTOR","version":__version__}),flush=True)
    first,referer,final=selected_lu()
    for label,address in (("selected_first_party_redirect",first),("selected_lulu_player",final)):
        opts={"skip_download":True,"quiet":True,"no_warnings":True,"socket_timeout":18,
              "http_headers":{"User-Agent":UA,"Referer":referer,"Accept-Encoding":"identity"},
              "ignoreerrors":False,"noplaylist":True}
        try:
            with yt_dlp.YoutubeDL(opts) as y:
                info=y.extract_info(address,download=False)
            formats=(info or {}).get("formats") or []
            print(json.dumps({"stage":label,"extractor":(info or {}).get("extractor_key","")[:80],
                              "formats":len(formats),"duration_known":bool((info or {}).get("duration")),
                              "status":"metadata_success"}),flush=True)
            if formats:
                return 0
        except Exception as e:
            name=type(e).__name__
            print(json.dumps({"stage":label,"status":"metadata_failed","error_type":name,
                              "auth_or_unsupported":"unsupported" if "Unsupported" in name else
                              "access_or_parser"}),flush=True)
    return 4

if __name__=="__main__":
    import sys
    try:sys.exit(main())
    except Exception as error:
        print(json.dumps({"stage":"SELECTED_LU_EXTRACTOR_PROBE_FAILED",
                          "error_type":type(error).__name__}),flush=True)
        sys.exit(5)
