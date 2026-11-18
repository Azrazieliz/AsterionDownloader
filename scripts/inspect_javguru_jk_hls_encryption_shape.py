#!/usr/bin/env python3
"""Report only selected final JK HLS transport structure (never key/token values)."""
import json,re,sys
from verify_javguru_owner_jk_full_pc import selected_final
from inspect_javguru_jk_hls_finality import manifest
def main():
    _,master,child,proof=selected_final()
    raw=manifest(child,master)
    keys=re.findall(r'(?m)^#EXT-X-KEY:\s*([^\r\n]+)',raw)
    maps=re.findall(r'(?m)^#EXT-X-MAP:\s*([^\r\n]+)',raw)
    print(json.dumps({"provider":"jav_guru","stage":"EXACT_JK_FINAL_HLS_STRUCTURE",
       "selected_duration_s":proof["runtime_s"],"segments":proof["media_segments"],
       "endlist_exact":raw.count("#EXT-X-ENDLIST"),
       "key_directive_count":len(keys),
       "encryption_methods":[m.group(1) for k in keys
              if (m:=re.search(r'METHOD=([A-Z0-9_-]+)',k))][:8],
       "iv_present":[bool(re.search(r'\bIV=',k)) for k in keys[:8]],
       "map_count":len(maps),
       "byterange_count":raw.count("#EXT-X-BYTERANGE"),
       "discontinuity_count":raw.count("#EXT-X-DISCONTINUITY"),
       "file_headers_only":True,"complete_file_verified":False}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"status":"JK_SELECTED_HLS_STRUCTURE_FAILED",
            "error_class":type(e).__name__}),flush=True)
        sys.exit(3)
