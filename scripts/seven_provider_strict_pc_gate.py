#!/usr/bin/env python3
"""Strict seven-provider PC gate; no APK build, phone mutation, or synthetic success.

Each provider uses its exact publisher-attested selected player. This harness
never counts an exit-zero probe as a full-file download. All media saved by
the full-file tests lives in temporary folders and is removed by the tests.
"""
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import json, re, subprocess, sys, time

ROOT=Path(__file__).resolve().parent
CASES={
 "jav_guru":("verify_javguru_owner_jk_https_segments_pc.py",1200,True),
 "javideo":("verify_javideo_selected_play_cdp.py",100,False),
 "helloporn":("verify_helloporn_selected_voe_media.py",100,False),
 "pornhub":("verify_pornhub_full_selected_pc.py",600,True),
 "hentaihaven":("verify_haven_full_selected_pc.py",600,True),
 "mp4hentai":("verify_mp4hentai_upfiles_public_form.py",100,False),
 "uncensoredhentai":("verify_uncensoredhentai_selected_full_pc.py",600,True),
}
def run(name, entry):
    script,timeout,expects_full=entry
    started=time.monotonic()
    try:
        proc=subprocess.run([sys.executable,"-B",str(ROOT/script)],
          cwd=ROOT.parent,timeout=timeout,stdin=subprocess.DEVNULL,
          capture_output=True,text=True,errors="replace")
        lines=(proc.stdout+"\n"+proc.stderr).splitlines()
        records=[]
        for line in lines:
            line=line.strip()
            if not line.startswith("{"):continue
            try:
                obj=json.loads(line)
                if isinstance(obj,dict):records.append(obj)
            except json.JSONDecodeError:continue
        receipts=[x for x in records if x.get("status")=="COMPLETE_SELECTED_PC_FILE"]
        full=proc.returncode==0 and len(receipts)==1 and (
           (receipts[0].get("bytes") or 0)>1_000_000) and (
           (receipts[0].get("duration_s") or receipts[0].get("duration_seconds") or 0)>60
           and receipts[0].get("video") is True)
        result={"provider":name,"script":script,"exit":proc.returncode,
          "elapsed_s":round(time.monotonic()-started,1),
          "full_original_verified":bool(full),
          "diagnostic_only":not expects_full,
          "source_probed":proc.returncode==0,
          "receipt":({"bytes":receipts[0]["bytes"],
                "duration":receipts[0].get("duration_s") or receipts[0].get("duration_seconds"),
                "sha256_prefix":receipts[0].get("sha256_prefix")}
                if full else None),
          "evidence_stage":[str(obj.get("stage") or obj.get("status"))
                    for obj in records[-9:]],
          "terminal_error":next((
                str(x.get("error_type") or x.get("type") or "")+":"+
                str(x.get("error_summary") or x.get("reason") or x.get("message") or "")[:120]
                for x in reversed(records)
                if x.get("status") in ("NOT_COMPLETE","NOT_VERIFIED","FAILED_NOT_COMPLETE")
                ),None)}
        return result
    except subprocess.TimeoutExpired:
        return {"provider":name,"exit":124,"elapsed_s":round(time.monotonic()-started,1),
             "full_original_verified":False,"diagnostic_only":not expects_full,"error":"timeout"}
    except Exception as e:
        return {"provider":name,"exit":125,"elapsed_s":round(time.monotonic()-started,1),
             "full_original_verified":False,"diagnostic_only":not expects_full,
             "error_type":type(e).__name__}
def main():
    results=[]
    # Bound PC bandwidth and CPU while maintaining independent player tests.
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures={pool.submit(run,name,case):name for name,case in CASES.items()}
        for future in as_completed(futures):
            row=future.result()
            results.append(row)
            print(json.dumps(row,ensure_ascii=True),flush=True)
    present={r["provider"]:r for r in results}
    complete=[k for k in CASES if present[k].get("full_original_verified")]
    summary={"type":"STRICT_PC_RELEASE_GATE","scope":"seven owner failures only",
      "total":len(CASES),"complete":len(complete),"completed_providers":complete,
      "not_complete":[k for k in CASES if k not in complete],
      "source_probes_do_not_count_as_full":True,
      "passed":len(complete)==len(CASES),
      "build_authorized_by_gate":len(complete)==len(CASES)}
    print(json.dumps(summary,ensure_ascii=True),flush=True)
    return 0 if summary["passed"] else 1
if __name__=="__main__":
    sys.exit(main())
