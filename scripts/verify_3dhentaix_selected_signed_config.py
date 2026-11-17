#!/usr/bin/env python3
"""Compare exact selected 3DHentaiX post's signed player in static vs rendered HTML.
No signed URLs/nonces exported; only source count, post ID and safe DOM shapes.
"""
import json,re,subprocess,tempfile,urllib.request
PAGE="https://3dhentaix.com/ntr-compilation/"
UA="Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
EDGE=r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
def summarize(tag,body):
    pattern=r"window\.awpSignedPlayback\s*=\s*(\{[^}]{12,1200}\})\s*;"
    configs=[]
    for match in re.finditer(pattern,body,re.I|re.S):
        try:
            item=json.loads(match.group(1))
            configs.append({"post_id":item.get("postId"),"nonce_valid":bool(re.fullmatch(
                r"[A-Za-z0-9_-]{6,64}",str(item.get("nonce",""))))})
        except Exception:
            configs.append({"json":False})
    print(json.dumps({"kind":tag,"html_bytes":len(body),
        "script_signature_mentions":body.count("awpSignedPlayback"),
        "configs":configs,"main_video_players":len(re.findall(
            r"<(?:main|article)[^>]*>[\s\S]{0,8000}class=['\"][^'\"]*video-player",body,re.I)),
        "video_player_classes":len(re.findall(r"class=['\"][^'\"]*video-player",body,re.I)),
        "post_article_ids":list(dict.fromkeys(re.findall(
            r"<article[^>]+id=['\"]post-(\d+)",body,re.I)))[:5],
        "challenge_seen":bool(re.search(
            "just a moment|verify you are human",body[:4000],re.I))}),flush=True)
try:
    with urllib.request.urlopen(urllib.request.Request(PAGE,headers={
        "User-Agent":UA,"Accept-Encoding":"identity"}),timeout=20) as r:
        static=r.read(1800000).decode("utf-8","replace")
        print("STATIC_HTTP",r.status,flush=True)
    summarize("STATIC",static)
except Exception as e:print("STATIC_ERROR",type(e).__name__,str(e)[:120],flush=True)
with tempfile.TemporaryDirectory(prefix="asterion_3d_selected_") as d:
    proc=subprocess.run([EDGE,"--headless=new","--disable-gpu","--mute-audio",
        "--disable-extensions","--no-first-run","--user-data-dir="+d,
        "--virtual-time-budget=9000","--dump-dom",PAGE],
        capture_output=True,timeout=35)
    print("EDGE_EXIT",proc.returncode,flush=True)
    summarize("RENDERED",proc.stdout.decode("utf-8","replace"))
