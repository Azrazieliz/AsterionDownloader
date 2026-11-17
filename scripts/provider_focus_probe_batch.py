#!/usr/bin/env python3
"""Parallel, bounded first-party provider evidence; no APK and no media saved.
Use the existing targeted probes rather than replacing the real adapters.
"""
import concurrent.futures,json,subprocess,sys,time,re
from pathlib import Path
ROOT=Path(__file__).resolve().parent
# Exactly the seven currently owner-reported failures. Archivebate is not
# a newly failing eighth provider; its standalone diagnostics remain available.
# These are SOURCE/PLAYER probes, not full-file acceptance tests.
CASES={
"jav_guru":("verify_javguru_exact_selected_hls.py",[]),
"javideo":("verify_javideo_selected_play_cdp.py",[]),
"helloporn":("verify_helloporn_selected_voe_media.py",[]),
"pornhub":("verify_pornhub_exact_selected_hls.py",[]),
"hentaihaven":("verify_haven_selected_episode_hls_20261117.py",[]),
"mp4hentai":("verify_mp4hentai_upfiles_public_form.py",[]),
"uncensoredhentai":("verify_nhplayer_selected_firstparty_20261117.py",[]),
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
def summarize(results):
    """Exit status is a PROBE result, never a full-original or Android receipt.

    Previously, this batch silently returned exit 0 even when individual
    providers failed (for example HelloPorn's real VOE probe exit 6).
    A timeout or a subprocess failure must fail the batch as well.
    """
    failed=[r["provider"] for r in results
            if r.get("exit")!=0 or r.get("status") in ("timeout","error")]
    passed=[r["provider"] for r in results if r.get("exit")==0]
    return {
        "type":"provider_probe_batch_summary",
        "total":len(results),
        "probes_completed":len(passed),
        "probes_failed":len(failed),
        "failed_providers":sorted(failed),
        "verification_scope":"PC selected-provider diagnostics only",
        "android_original_receipts_verified":False,
        "exit":1 if failed else 0,
    }


def main(cases=None):
    if cases is None:
        cases=CASES
    results=[]
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        for item in concurrent.futures.as_completed(
                [pool.submit(run,kv) for kv in cases.items()]):
            result=item.result()
            results.append(result)
            # Per-provider return code represents diagnostic completion only.
            # It does not attest that the chosen original file was saved.
            print(json.dumps({
                **result,
                "status":("probe_completed_unverified" if result.get("exit")==0
                    else result.get("status","probe_failed")),
                "evidence":[line[:260] for line in result.get("evidence",[])][:9],
                "android_original_receipt_verified":False,
            },ensure_ascii=True),flush=True)
    summary=summarize(results)
    print(json.dumps(summary,ensure_ascii=True),flush=True)
    return summary["exit"]


if __name__=="__main__":
    sys.exit(main())
