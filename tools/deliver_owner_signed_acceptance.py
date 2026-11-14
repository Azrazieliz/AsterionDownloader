#!/usr/bin/env python3
"""Transfer the retained-owner-signed unified APK to the paired Samsung.

Asterion Control native transport is the only delivery channel. The receipt
is checked against the complete APK SHA256, not upload initiation. Android's
package installer remains user-authorized; transfer != installation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

import urllib.request
import urllib.error


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fp:
        for block in iter(lambda: fp.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=r"D:\AsterionDownloader")
    parser.add_argument("--control", default="http://192.168.1.133:8911")
    parser.add_argument("--input", default=None)
    args = parser.parse_args()

    root = Path(args.repo)
    original = Path(args.input) if args.input else (
        root / "app" / "build" / "outputs" / "apk" / "acceptance" / "app-acceptance.apk")
    if not original.is_file() or original.stat().st_size < 1_000_000:
        raise RuntimeError("Built owner-signed acceptance APK is missing")
    commit = subprocess.check_output(
        ["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if len(commit) != 40:
        raise RuntimeError("Source commit does not match a full Git SHA")
    filename = f"Asterion-Downloader-v1.3.60-{commit[:8]}-Owner-Signed-Acceptance.apk"
    source_digest = sha256(original)
    token_path = (Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) /
                  "AsterionControl" / "control-token.txt")
    token = token_path.read_text("utf-8").strip()
    if not token:
        raise RuntimeError("Asterion Control token is empty")
    # Use only Python stdlib: the owner's Windows Python has no httpx.
    # Disable ambient HTTP proxies; this is the paired private Control host.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    auth = {"Authorization": "Bearer " + token}
    # The Control server enforces configured file roots. The old hardcoded
    # D:/AsterionControlRelease/builds staging path returned HTTP 403:
    # "Path outside configured roots". Use the currently advertised owner
    # root and remove our temporary copy after a verified Samsung receipt.
    health_req = urllib.request.Request(
        args.control + "/api/v1/health", method="GET", headers=auth)
    with opener.open(health_req,timeout=12) as res:
        health = json.load(res)
    if not health.get("ok"):
        raise RuntimeError("Authenticated Control health is unavailable")
    control_root = Path(str(health.get("root","")))
    if not control_root.is_dir():
        raise RuntimeError("Control did not advertise a usable configured root")
    destination = control_root / "Downloads" / "Asterion Control" / filename
    destination.parent.mkdir(parents=True,exist_ok=True)
    headroom = shutil.disk_usage(destination.parent).free
    if (not destination.exists() and
            headroom < original.stat().st_size + 60 * 1024 * 1024):
        raise RuntimeError("PC system drive too full for temporary native transfer")
    created = False
    if destination.is_file():
        if sha256(destination) != source_digest:
            raise RuntimeError("Allowed-root destination already holds different APK")
    else:
        shutil.copy2(original,destination)
        created = True
    if sha256(destination) != source_digest:
        raise RuntimeError("Allowed-root staged APK digest mismatch")
    payload = json.dumps({"pc_path": str(destination), "name": filename}).encode("utf-8")
    request = urllib.request.Request(
        args.control + "/api/v1/phone/transfers",
        data=payload, method="POST",
        headers={**auth, "Content-Type": "application/json"})
    try:
        with opener.open(request, timeout=20) as res:
            started = json.load(res)
    except urllib.error.HTTPError as exc:
        detail = exc.read(2_048).decode("utf-8","replace")
        # Control's structured status response is useful for distinguishing
        # an invalid path/permission from an ordinary expired owner token.
        print("CONTROL_TRANSFER_HTTP_STATUS="+str(exc.code))
        print("CONTROL_TRANSFER_DENIAL="+detail[:800])
        raise
    transfer_id = started.get("id")
    if not transfer_id:
        raise RuntimeError("Control transfer request gave no transfer ID")
    info = None
    for _ in range(2000):
        request = urllib.request.Request(
            args.control + "/api/v1/phone/transfers/" + transfer_id,
            headers=auth, method="GET")
        with opener.open(request, timeout=20) as res:
            info = json.load(res)
        if info.get("state") in ("completed", "failed", "cancelled"):
            break
        time.sleep(0.25)
    if not info or info.get("state") != "completed":
        raise RuntimeError("Control delivery not complete: " +
                           str((info or {}).get("state")))
    proof = info.get("result") or {}
    verified = str(proof.get("sha256", "")).lower()
    if verified != source_digest:
        raise RuntimeError("Samsung SHA256 receipt mismatch")
    if (info.get("bytes_sent") is not None and
            int(info["bytes_sent"]) != destination.stat().st_size):
        raise RuntimeError("Samsung transfer byte count mismatch")

    print(json.dumps({
        "status": "transferred_not_installed",
        "commit": commit,
        "apk": filename,
        "size_bytes": destination.stat().st_size,
        "sha256_verified": True,
        "android_location": proof.get("location"),
        "target": "paired_owner_phone",
        "user_install_approval_required": True
    }, indent=2))
    if created:
        # Only the exact temp APK created by THIS run may be removed.
        # The signed source and GitHub release asset remain untouched.
        if sha256(destination) == source_digest:
            destination.unlink()


if __name__ == "__main__":
    main()
