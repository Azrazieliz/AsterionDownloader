"""Deliver one owner-signed Asterion APK to the paired phone over existing private Wi-Fi.
No ADB and no GitHub file payload; trust Android's final SHA-256 MediaStore receipt.
"""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, "D:/AsterionControlRelease")
from asterion_remote.native_transfer import (
    TransferRequest, start_transfer, transfer_status,
)

def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: native_wifi_owner_apk_transfer.py APK FILENAME")
    original = Path(sys.argv[1]).resolve(strict=True)
    name = sys.argv[2]
    if original.suffix.lower() != ".apk" or original.is_symlink():
        raise ValueError("Only the exact signed APK is permitted")
    expected = hashlib.sha256(original.read_bytes()).hexdigest()
    size = original.stat().st_size

    def exact_source(value: str) -> Path:
        resolved = Path(value).resolve(strict=True)
        if resolved != original or resolved.is_symlink():
            raise ValueError("Asterion Control attempted another source file")
        return resolved

    identifier = start_transfer(
        TransferRequest(pc_path=str(original), name=name), exact_source
    )["id"]
    deadline = time.monotonic() + 450
    while time.monotonic() < deadline:
        report = transfer_status(identifier)
        if report["state"] in ("completed", "failed", "cancelled", "outcome_unknown"):
            break
        time.sleep(0.75)
    else:
        raise TimeoutError("Android private Wi-Fi transfer did not finish")
    result = report.get("result") or {}
    if not (
        report["state"] == "completed"
        and result.get("verified") is True
        and result.get("sha256") == expected
        and int(result.get("size", -1)) == size
        and str(result.get("android_uri", "")).startswith("content://")
        and str(result.get("location", "")).startswith("Downloads/Asterion Control/")
    ):
        raise RuntimeError(
            "Android checksum/publication not verified: "
            + str(report.get("error")) + " / " + str(report["state"])
        )
    print(json.dumps({
        "verified_phone_file": result["location"],
        "verified_phone_uri": result["android_uri"],
        "sha256": expected,
        "size": size,
        "transport": "existing-private-wifi",
        "installed": False,
    }, separators=(",", ":")), flush=True)

if __name__ == "__main__":
    main()
