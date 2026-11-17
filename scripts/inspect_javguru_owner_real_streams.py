#!/usr/bin/env python3
"""Public exact MXGS-1452 original-source provenance across its ACTUAL STREAM servers.

The selected WordPress post has SB, VO, DD and JK (not LU). Follow only
publisher-owned shortcode config -> first-party /searcho/ -> publisher-attested
hoster. Diagnostic-only: HTML/player success is never complete media.
"""
import base64
import json
import re
import sys
import urllib.parse
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from verify_javguru_exact_selected_hls import Doc, get, value

WATCH=("https://jav.guru/1077276/mxgs-1452-meruty-kiss-drooling-saliva-melting-french-kiss-full-course-sato-meru/")
PREFERRED=["VO","SB","DD","JK"]
def run():
    status,final,page=get(WATCH,WATCH)
    if status!=200 or urllib.parse.urlsplit(final).hostname!="jav.guru":
        return 4
    doc=Doc();doc.feed(page)
    controls={x["key"]:x["label"].strip().upper().replace("STREAM ","")
              for x in doc.buttons if x["label"].strip().upper().startswith("STREAM ")}
    publisher=[]
    for js in doc.code:
        for m in re.finditer(r"\bvar\s+([a-z]{10})\s*=\s*(\{[^}]{20,1600}\})",js,re.S):
            key=m.group(1)
            if key not in controls or controls[key] not in PREFERRED:continue
            try:
                raw=json.loads(m.group(2))["iframe_url"]
                relay=base64.b64decode(raw,validate=True).decode("utf8")
                u=urllib.parse.urlsplit(relay)
                if (u.scheme!="https" or u.hostname!="jav.guru" or u.path!="/searcho/" or
                    not re.fullmatch(r"[a-z]{2}=[a-fA-F0-9]{10,150}(?:&bg=https?%3A%2F%2F[^&]{1,400})?",u.query)):
                    continue
                publisher.append((controls[key],relay))
            except (KeyError,ValueError,UnicodeError):
                continue
    unique={tag:relay for tag,relay in publisher}
    if not unique:
        structures=[]
        for js in doc.code:
            if "iframe_url" not in js:continue
            declarations=re.findall(r"\b(?:var|let|const)\s+([A-Za-z_$][\w$]{1,50})\s*=",js)
            signatures={
                "inline_decl_types":{keyword:len(re.findall(r"\b"+keyword+r"\s+",js))
                    for keyword in ("var","let","const")},
                "inline_declaration_name_lengths":[len(x) for x in declarations[:20]],
                "button_keys_in_script":sum(str(key) in js for key in controls),
                "iframe_url_count":js.count("iframe_url"),
                "json_quoted_key":bool(re.search(r"['\"]iframe_url['\"]\s*:",js)),
                "bare_key":bool(re.search(r"\biframe_url\s*:",js)),
                "base64_call":bool(re.search(r"\b(?:atob|btoa)\s*\(",js)),
                "iframe_url_assignment":bool(re.search(r"\biframe_url\s*=",js)),
                "code_chars":len(js),
                "declaration_keywords":len(declarations)}
            structures.append(signatures)
        shape=[]
        for js in doc.code:
            if "iframe_url" not in js:continue
            for m in re.finditer(r"\bvar\s+([A-Za-z_$][\w$]{1,50})\s*=\s*(\{[^}]{20,2500}\})",js,re.S):
                name=m.group(1)
                try:
                    item=json.loads(m.group(2))
                    raw=item.get("iframe_url","")
                    decoded=base64.b64decode(raw,validate=True).decode("utf8")
                    uri=urllib.parse.urlsplit(decoded)
                    shape.append({"server":controls.get(name,"unmatched"),
                        "key_matches_button":name in controls,"config_valid":True,
                        "scheme":uri.scheme,"host":uri.hostname,
                        "route":uri.path[:35],
                        "query_keys":sorted(urllib.parse.parse_qs(uri.query)),
                        "encoded_length":len(raw)})
                except Exception as error:
                    shape.append({"server":controls.get(name,"unmatched"),
                        "key_matches_button":name in controls,"config_valid":False,
                        "error_type":type(error).__name__})
        print(json.dumps({"stage":"EXACT_STREAM_CONFIG_STRUCTURE",
            "configs":structures[:5],"shortcode_shapes":shape[:10],
            "redacted_values":True}),flush=True)
    print(json.dumps({"stage":"ACTUAL_SELECTED_STREAM_BUTTONS","post":1077276,
        "buttons":list(controls.values()),"shortcode_candidates":list(unique)}),flush=True)
    if not unique:return 3
    accepted=0
    for tag in PREFERRED:
        if tag not in unique:continue
        try:
            code,land,wrapper=get(unique[tag],WATCH)
            if code!=200:raise RuntimeError("selected relay denied")
            w=Doc();w.feed(wrapper)
            scripts=[s for s in w.code if "window.cfg" in s and "fullToken.rev()" in s]
            if len(scripts)!=1:raise RuntimeError("relay JS not unique")
            js=scripts[0]
            cid=value(js,"cid");base=value(js,"base");rtype=value(js,"rtype")
            m=re.search(r"\bkeys\s*:\s*\[\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*\]",js)
            print(json.dumps({"server":tag,"stage":"ACTUAL_RELAY_JS_SHAPE",
                "has_cid":bool(cid),"cid_element_exists":cid in w.nodes if cid else False,
                "base_host":urllib.parse.urlsplit(base or "").hostname,
                "base_path":urllib.parse.urlsplit(base or "").path,
                "rtype":rtype,"has_keys":m is not None,
                "relay_scripts":len(w.code),"inline_config_scripts":len(scripts),
                "markers":{"window_cfg":wrapper.count("window.cfg"),
                    "fullToken.rev":wrapper.count("fullToken.rev"),
                    "activate_real_stream":wrapper.count("activate_real_stream")},
                "wrapper_status":code}),flush=True)
            if(not cid or cid not in w.nodes or base!="https://jav.guru/searcho/"
                or rtype not in ("e","v","s","x","t","h","o") or m is None):
                raise RuntimeError("first-party relay configuration absent")
            node=w.nodes[cid]
            parts=[node.get(key) for key in m.groups()]
            if not all(part and re.fullmatch(r"[A-Za-z0-9]{5,55}",part) for part in parts):
                raise RuntimeError("source token not publisher-attested")
            selected=base+"?"+rtype+"r="+("".join(parts)[::-1])
            code,land,markup=get(selected,unique[tag],950000)
            host=urllib.parse.urlsplit(land).hostname
            if code!=200 or not host or host.endswith(".jav.guru") or host=="jav.guru":
                raise RuntimeError("selected external hoster unavailable")
            flags={
                "html_video_tags":len(re.findall(r"<video\b",markup,re.I)),
                "html_source_tags":len(re.findall(r"<source\b",markup,re.I)),
                "hls_literals":markup.lower().count(".m3u8"),
                "mp4_literals":markup.lower().count(".mp4"),
                "packed_jwplayer":markup.count("eval(function(p,a,c,k,e,d)"),
                "external_iframes":len(re.findall(r"<iframe\b",markup,re.I))}
            print(json.dumps({"server":tag,"status":"PUBLISHED_HOSTER_HTML_ONLY",
                "final_host":host,"html_bytes":len(markup),**flags}),flush=True)
            accepted+=1
        except Exception as error:
            print(json.dumps({"server":tag,"status":"SELECTED_PLAYER_NOT_RESOLVED",
                 "error_type":type(error).__name__,
                 "reason":str(error)[:125]}),flush=True)
    print(json.dumps({"stage":"OWNER_SOURCE_DIAGNOSTIC_ONLY",
       "hoster_pages":accepted,"full_original_verified":False}),flush=True)
    return 0 if accepted else 5

if __name__=="__main__":
    try:sys.exit(run())
    except Exception as e:
        print(json.dumps({"status":"EXACT_OWNER_JAV_GURU_UNVERIFIED",
            "type":type(e).__name__,"reason":str(e)[:150]}),flush=True)
        sys.exit(6)
