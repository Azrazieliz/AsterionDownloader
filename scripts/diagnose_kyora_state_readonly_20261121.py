#!/usr/bin/env python3
"""Read-only snapshot of installed GalleryFlow state across every provider.

Copies no database back to GitHub and prints only aggregate source statuses.
Originals, app settings, MediaStore and databases remain unchanged.
"""
import argparse
import pathlib
import sqlite3
import subprocess
import tempfile

def main():
    args = argparse.ArgumentParser()
    args.add_argument("--device", required=True)
    args.add_argument("--package", default="com.azrael.pixivdumpsync.acceptance")
    opts = args.parse_args()
    with tempfile.TemporaryDirectory(prefix="kyora_readonly_") as folder:
        root = pathlib.Path(folder)
        for name in ("galleryflow.db", "galleryflow.db-wal", "galleryflow.db-shm"):
            cmd = ["adb", "-s", opts.device, "exec-out", "run-as",
                   opts.package, "cat", "databases/" + name]
            proc = subprocess.run(cmd, capture_output=True, timeout=25)
            if proc.returncode == 0 and proc.stdout:
                (root / name).write_bytes(proc.stdout)
                print("SNAPSHOT", name, "bytes", len(proc.stdout), flush=True)
        file = root / "galleryflow.db"
        if not file.is_file() or file.stat().st_size < 100:
            raise RuntimeError("No readable installed GalleryFlow DB snapshot")
        con = sqlite3.connect("file:" + str(file) + "?mode=ro", uri=True)
        try:
            reports = (
                ("MEDIA_STATE", "SELECT source,state,count(*) FROM media GROUP BY source,state ORDER BY source,state"),
                ("GALLERY_STATE", "SELECT source,state,count(*) FROM galleries GROUP BY source,state ORDER BY source,state"),
                ("SUBSCRIPTIONS", "SELECT source,kind,selected,live_enabled,archived,count(*) FROM entities GROUP BY source,kind,selected,live_enabled,archived ORDER BY source,kind"),
                ("SOURCE_ERRORS", "SELECT source,substr(last_error,1,120),count(*) FROM entities WHERE last_error IS NOT NULL AND length(last_error)>0 GROUP BY source,substr(last_error,1,120) ORDER BY source LIMIT 25"),
                ("RETRY_JOBS", "SELECT source,count(*) FROM backfill_retries GROUP BY source ORDER BY source"),
                ("UNSAVED_BYTES", "SELECT source,state,count(*) FROM media WHERE content_uri IS NULL GROUP BY source,state ORDER BY source,state")
            )
            for title, query in reports:
                print(title, flush=True)
                for entry in con.execute(query).fetchall():
                    print(repr(entry), flush=True)
        finally:
            con.close()

if __name__ == "__main__":
    main()
