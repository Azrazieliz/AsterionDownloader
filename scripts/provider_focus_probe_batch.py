#!/usr/bin/env python3
"""Parallel, bounded first-party provider evidence; no APK and no media saved.
Use the existing targeted probes rather than replacing the real adapters.
"""
import concurrent.futures,json,subprocess,sys,time,re
from pathlib import Path
ROOT=Path(__file__).resolve().parent
CASES={
"archivebate":("probe_archivebate_real_selected_watch.py",[]),
"jav_guru":("verify_javguru_exact_selected_hls.py",[]),
"pornhub":("verify_pornhub_exact_selected_hls.py",[]),
"javideo":("verify_javideo_selected_play_cdp.py",[]),
"helloporn":("verify_helloporn_selected_voe_media.py",[]),
"uncensoredhentai":("selected_player_contract_probe.py",[]),
"hentaihaven":("inspect_provider_download_anchors.py",["hentaihaven_selected"]),
"mp4hentai":("inspect_mp4hentai_actual_hoster_second_stage.py",[]),
}
def run(kv):
    name,(script,args)=kv
    started=time.monotonic()
    try:
        proc=subprocess.run([sys.executable,str(ROOT/script),*args],
            capture_output=True,text=True,timeout=82,
            stdin=subprocess.DEVNULL,cwd=str(ROOT.parent),errors="replace")
        output=(proc.stdout+"\n"+proc.stderr).splitlines()
        safe=[]
        for line in output[-15:]:
            line=re.sub(r"https?://[^\s\"'<>]+","[source-url]",line)
            line=re.sub(r"(?i)(cookie|bearer|token|signature|password|api_key)[=:]\S+","[redacted]",line)
            safe.append(line[:850])
        return {"provider":name,"exit":proc.returncode,"elapsed_s":round(time.monotonic()-started,1),"evidence":safe}
    except subprocess.TimeoutExpired:
        return {"provider":name,"status":"timeout","elapsed_s":round(time.monotonic()-started,1)}
    except Exception as error:
        return {"provider":name,"status":"error","reason_class":type(error).__name__}
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
    for item in concurrent.futures.as_completed([pool.submit(run,kv) for kv in CASES.items()]):
        result=item.result()
        print(json.dumps({**result,"evidence":[line[:260] for line in result.get("evidence",[])][:9]},ensure_ascii=True),flush=True)
