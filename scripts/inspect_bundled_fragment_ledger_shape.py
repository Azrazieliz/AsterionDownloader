#!/usr/bin/env python3
"""Read the exact bundled yt-dlp fragment-ledger implementation, offline."""
from pathlib import Path
import zipfile
p=Path(__file__).resolve().parents[1]/"app/src/main/assets/yt_dlp-2026.10.26-py3-none-any.whl"
with zipfile.ZipFile(p) as z:
 body=z.read("yt_dlp/downloader/fragment.py").decode("utf8","replace")
for key in ("def _write_ytdl_file",'"current_fragment"',"'current_fragment'",'"fragment_count"',"'fragment_count'"):
 i=body.find(key)
 print("KEY",key,"AT",i)
 if i>=0:print(body[max(0,i-160):i+900])
