#!/usr/bin/env python3
"""Safe publisher-selected Jav Guru SB links object keys + CDN route shape."""
import json,re,urllib.parse
from verify_javguru_owner_sb_full_pc import selected_player
from verify_roshy_selected_packed_hls import Script,deobfuscate
def main():
    player,html=selected_player()
    scan=Script();scan.feed(html)
    for source in scan.raw:
        if "eval(function(p,a,c,k,e,d)" not in source:continue
        decoded=deobfuscate(source)
        if not decoded:continue
        for match in re.finditer(r"\bvar\s+links\s*=\s*",decoded):
            try:obj,_=json.JSONDecoder().raw_decode(decoded[match.end():])
            except (ValueError,TypeError):continue
            if not isinstance(obj,dict):continue
            out={}
            for key in ("hls4","hls3","hls2"):
                value=obj.get(key)
                if not isinstance(value,str):continue
                p=urllib.parse.urlsplit(value.replace("\\/","/"))
                segments=[x for x in p.path.split("/") if x]
                out[key]={"scheme":p.scheme,"host":p.hostname,
                    "path_prefix":segments[:2],
                    "extension":p.path.rsplit(".",1)[-1][:12],
                    "query_keys":sorted(urllib.parse.parse_qs(p.query)),
                    "relative_to_player":p.hostname==urllib.parse.urlsplit(player).hostname}
            print(json.dumps({"stage":"SB_SELECTED_LINKS_OBJECT",
                 "publisher_player":urllib.parse.urlsplit(player).hostname,
                 "qualities":out,"links_object_count":decoded.count("var links="),
                 "jwplayer_selected_expression":bool(re.search(
                     r"file:\s*links\.hls4\s*\|\|\s*links\.hls3\s*\|\|\s*links\.hls2",decoded))}),flush=True)
if __name__=="__main__":main()
