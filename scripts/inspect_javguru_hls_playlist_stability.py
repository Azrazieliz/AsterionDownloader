#!/usr/bin/env python3
"""Explain owner-selected JavGuru SB playlist growth vs completed-file duration.

The same selected publisher MXGS-1452 signed master and 480p rendition are
sampled twice through the normal public CDN, without storing media or
revealing private/signed URLs. Source playlist end markers and stability
determine whether an HLS snapshot is really a final VOD.
"""
import json,re,sys,time,urllib.parse,urllib.request
from verify_javguru_owner_sb_full_pc import selected_player,selected_hls,UA
def get(url,ref):
    started=time.monotonic()
    request=urllib.request.Request(url,headers={"User-Agent":UA,"Referer":ref,
         "Accept-Encoding":"identity"})
    with urllib.request.urlopen(request,timeout=25) as response:
        if response.status!=200:raise RuntimeError("selected manifest HTTP not 200")
        data=response.read(200000).decode("utf8","replace")
        if not data.startswith("#EXTM3U"):
            raise RuntimeError("selected page did not return HLS")
        return data,round(time.monotonic()-started,2)
def main():
    embed,page=selected_player()
    master=selected_hls(embed,page)
    if not master:raise RuntimeError("no publisher selected SB source")
    source,_=get(master,embed)
    lines=[v.strip() for v in source.splitlines()]
    opts=[]
    for ix,entry in enumerate(lines[:-1]):
        if not entry.startswith("#EXT-X-STREAM-INF") or not re.search(
                r"RESOLUTION=[0-9]+x480(?:,|$)",entry):continue
        url=urllib.parse.urljoin(master,lines[ix+1])
        parsed=urllib.parse.urlsplit(url)
        parent=urllib.parse.urlsplit(master)
        if(parsed.scheme=="https" and parsed.hostname==parent.hostname and
           parsed.path.endswith(".m3u8") and not parsed.fragment):
            opts.append(url)
    if len(opts)!=1:raise RuntimeError("not one same-publisher 480p rendition")
    def sample():
        body,elapsed=get(opts[0],master)
        ls=[v.strip() for v in body.splitlines()]
        media=[v for v in ls if v and not v.startswith("#")]
        durations=[float(v) for v in re.findall(
            r"#EXTINF:([0-9]+(?:\.[0-9]+)?)",body)]
        types=[v.split(":",1)[-1] for v in ls if v.startswith("#EXT-X-PLAYLIST-TYPE:")]
        seq=[v.split(":",1)[-1] for v in ls if v.startswith("#EXT-X-MEDIA-SEQUENCE:")]
        return {
           "endlist":any(v=="#EXT-X-ENDLIST" for v in ls),
           "playlist_types":types[:2],
           "media_sequence":seq[:2],
           "segment_count":len(media),
           "duration_sum_s":round(sum(durations),3),
           "last_segment_marker":media[-1] if media else None,
           "http_read_s":elapsed,
           "discontinuity_count":body.count("#EXT-X-DISCONTINUITY")
        }
    first=sample()
    time.sleep(10)
    second=sample()
    grew=first["segment_count"]!=second["segment_count"]
    last_changed=first["last_segment_marker"]!=second["last_segment_marker"]
    first.pop("last_segment_marker",None)
    second.pop("last_segment_marker",None)
    print(json.dumps({"provider":"jav_guru","stage":"PUBLISHER_HLS_SNAPSHOT_STABILITY",
      "selected_quality_height":480,"first":first,"second":second,
      "segment_count_changed":grew,"last_segment_changed":last_changed,
      "vod_endlist_confirmed":first["endlist"] and second["endlist"],
      "completed_original_receipt":False}),flush=True)
    return 0
if __name__=="__main__":
    try:sys.exit(main())
    except Exception as e:
        print(json.dumps({"provider":"jav_guru","stage":"HLS_STABILITY_UNVERIFIED",
            "error_type":type(e).__name__}),flush=True)
        sys.exit(5)
