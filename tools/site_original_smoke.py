#!/usr/bin/env python3
"""One exact, publisher-origin media page at a time; no circumvention.

Metadata alone is NOT accepted as a complete download. A temporary full media
download is attempted only for small, publicly accessible test items and is
deleted immediately after the receipt is checked.
"""
import argparse
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import tempfile
import urllib.parse


def run(args, timeout=60, cwd=None):
    try:
        r = subprocess.run(args, capture_output=True, text=True,
                           encoding="utf-8", errors="replace",
                           timeout=timeout, cwd=cwd, check=False)
        return r.returncode, r.stdout, r.stderr
    except subprocess.TimeoutExpired:
        return -1, "", f"TIMEOUT_{timeout}s"


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--url", required=True)
    p.add_argument("--max-seconds", type=int, default=180)
    p.add_argument("--max-bytes", type=int, default=30 * 1024 * 1024)
    p.add_argument("--metadata-only", action="store_true")
    opt = p.parse_args()
    url = urllib.parse.urlsplit(opt.url)
    if url.scheme != "https" or not url.hostname or url.username:
        raise SystemExit("INVALID_HTTPS_ORIGIN")
    args = [sys.executable, "-m", "yt_dlp", "--skip-download",
            "--dump-single-json", "--no-playlist", "--no-warnings",
            "--socket-timeout", "12", "--extractor-retries", "0",
            "--retries", "0", opt.url]
    ret, stdout, stderr = run(args, timeout=60)
    print("SOURCE_HOST", url.hostname)
    if ret != 0:
        print("METADATA_FAILED", (" ".join(stderr.splitlines()))[:180])
        raise SystemExit(2)
    try:
        data = json.loads(stdout)
    except json.JSONDecodeError:
        print("METADATA_INVALID_JSON")
        raise SystemExit(2)
    formats = [f for f in data.get("formats", []) if isinstance(f, dict)]
    duration = data.get("duration")
    print("METADATA", "PASS", "extractor", data.get("extractor_key", ""),
          "duration", duration, "formats", len(formats))
    if opt.metadata_only:
        return
    if not isinstance(duration, (int, float)) or duration <= 0 or duration > opt.max_seconds:
        print("FULL_TRANSFER", "SKIPPED_DURATION_OUTSIDE_LIMIT")
        return
    mp4 = [f for f in formats if f.get("ext") == "mp4" and
           f.get("protocol") in ("https", "http") and f.get("url")]
    if not mp4:
        print("FULL_TRANSFER", "SKIPPED_NO_DIRECT_MP4_FORMAT")
        return
    best = sorted(mp4, key=lambda f: (int(f.get("height") or 0),
                                    int(f.get("tbr") or 0)), reverse=True)[0]
    predicted = best.get("filesize") or best.get("filesize_approx")
    if predicted and int(predicted) > opt.max_bytes:
        print("FULL_TRANSFER", "SKIPPED_ESTIMATED_SIZE_LIMIT")
        return
    with tempfile.TemporaryDirectory(prefix="asterion_verified_original_") as d:
        output = str(pathlib.Path(d) / "verified.%(ext)s")
        cmd = [sys.executable, "-m", "yt_dlp", "--no-playlist",
               "--no-cache-dir", "--no-warnings", "--socket-timeout", "12",
               "--retries", "1", "-f", str(best["format_id"]),
               "-o", output, opt.url]
        ret, _, errors = run(cmd, timeout=120, cwd=d)
        files = [f for f in pathlib.Path(d).iterdir()
                 if f.suffix.lower() == ".mp4" and f.is_file()]
        if ret != 0 or len(files) != 1:
            print("FULL_TRANSFER", "FAILED", "returncode", ret,
                  (" ".join(errors.splitlines()))[:150])
            raise SystemExit(3)
        f = files[0]
        n = f.stat().st_size
        if n > opt.max_bytes or n < 8192:
            print("FULL_TRANSFER", "FAILED_SIZE", n)
            raise SystemExit(3)
        with f.open("rb") as stream:
            head = stream.read(12)
        if head[4:8] != b"ftyp":
            print("FULL_TRANSFER", "FAILED_MP4_SIGNATURE")
            raise SystemExit(3)
        digest = hashlib.sha256(f.read_bytes()).hexdigest()
        print("FULL_TRANSFER", "PASS", "bytes", n,
              "mp4_ftyp", True, "sha256", digest,
              "temporary_original_deleted_after_test", True)


if __name__ == "__main__":
    main()
