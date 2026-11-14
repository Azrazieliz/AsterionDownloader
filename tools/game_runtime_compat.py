"""Prepare source-free game archives for real Android compatibility runtimes.

This is not native binary conversion. Originals are preserved byte-for-byte;
every output declares the required runtime and any missing dependencies.
"""
from __future__ import annotations

from pathlib import Path
import hashlib
import json
import shutil


ROUTES = {
    "RENPY": ("JoiPlay", "Ren'Py plugin", "https://www.joiplay.net/",
              "Choose the exported game root in JoiPlay and select its Ren'Py plugin."),
    "RPG_MAKER_LEGACY": ("JoiPlay", "RPG Maker plugin",
                         "https://www.joiplay.net/",
                         "Import the game executable with the RPG Maker plugin."),
    "KIRIKIRI": ("Kirikiri-compatible Android interpreter", None, None,
                 "Import the original .xp3 archives in a compatible Kirikiri Android interpreter."),
    "NSCRIPTER": ("ONScripter-compatible Android interpreter", None, None,
                   "Import the original NScripter script and resource archives."),
    "WINDOWS": ("Winlator", "Windows compatibility environment",
                 "https://winlator.org/index.php",
                 "Copy the complete game folder into a Winlator-accessible directory, then configure an x86/x64 container and launch the game EXE."),
    "UNITY_WINDOWS": ("Winlator", "Windows compatibility environment",
                       "https://winlator.org/index.php",
                       "Run the original Windows Unity executable in Winlator; engine source is required for a real native APK."),
    "UNREAL_WINDOWS": ("Winlator", "Windows compatibility environment",
                        "https://winlator.org/index.php",
                        "Use the Windows game binaries and complete cooked data in Winlator; a native Android build requires source."),
    "GODOT_EXPORT": ("JoiPlay", "Matching Godot plugin",
                      "https://www.joiplay.net/",
                      "Import with a compatible Godot runtime. Exported PCK files are not the editable project."),
    "NINTENDO_3DS": ("Azahar", "Nintendo 3DS emulator", None,
                      "Import the legally held 3DS game file with a compatible emulator."),
    "NINTENDO_DS": ("Nintendo DS emulator", None, None,
                     "Open the legally held NDS file with a compatible emulator."),
    "UNITY": ("Winlator", "Windows game files only if executable is included",
               "https://winlator.org/index.php",
               "Asset bundles alone are not a playable complete Unity game."),
    "UNREAL_OR_OTHER": ("No verified generic runtime", None, None,
                         "Loose PAK assets alone do not constitute a runnable Android game."),
    "UNKNOWN": ("Unidentified", None, None,
                "Provide a complete game distribution; do not fabricate a port."),
}

def choose_entrypoint(paths: list[str], engine: str) -> str | None:
    candidates = [s.replace("\\", "/") for s in paths]
    if engine in {"WINDOWS", "UNITY_WINDOWS", "UNREAL_WINDOWS", "RPG_MAKER_LEGACY", "RENPY"}:
        matches = [p for p in candidates if p.lower().endswith(".exe") and
                   not any(term in p.lower().split("/")[-1] for term in
                           ("uninstall", "crash", "update", "setup", "config", "redist"))]
        if matches:
            return sorted(matches, key=lambda p: (p.count("/"), len(p), p.lower()))[0]
    ext = {
        "RENPY": (".rpy", ".rpyc", ".rpa"),
        "KIRIKIRI": (".xp3",),
        "NSCRIPTER": (".nsa", ".ns2"),
        "GODOT_EXPORT": (".pck",),
        "NINTENDO_3DS": (".3ds", ".cci", ".cia"),
        "NINTENDO_DS": (".nds",),
    }.get(engine, ())
    for extension in ext:
        hits = [p for p in candidates if p.lower().endswith(extension)]
        if hits:
            return sorted(hits, key=lambda p: (p.count("/"), len(p), p.lower()))[0]
    return None

def prepare(source: Path, output: Path, engine: str, paths: list[str],
            archive_members=None, extract=None) -> dict:
    if engine not in ROUTES:
        raise ValueError("No source-free compatibility route: " + engine)
    root = output / "compatibility-game"
    if root.exists():
        raise FileExistsError("Prepared game already exists; refusing overwrite")
    root.mkdir(parents=True)
    if source.is_dir():
        # Do not symlink a mutable source; preserve the original, including
        # arbitrary DLLs, configuration and game resource files.
        shutil.copytree(source, root / "files", dirs_exist_ok=True,
                        symlinks=False)
    elif source.suffix.lower() == ".zip":
        if archive_members is None or extract is None:
            raise ValueError("Validated archive members and extractor required")
        from zipfile import ZipFile
        with ZipFile(source) as archive:
            extract(archive, archive_members, root / "files")
    elif source.is_file():
        folder = root / "files"
        folder.mkdir()
        shutil.copy2(source, folder / source.name)
    else:
        raise ValueError("Missing original game data")
    runtime, plugin, url, instructions = ROUTES[engine]
    entry = choose_entrypoint(paths, engine)
    incomplete = bool(source.is_file() and source.suffix.lower() != ".zip" and
                      engine in {"WINDOWS", "UNITY_WINDOWS", "UNREAL_WINDOWS"})
    manifest = {
        "format_version": 1, "engine": engine,
        "result": "compatibility_payload_not_native_apk",
        "runtime": runtime, "plugin": plugin,
        "runtime_url": url, "import_instructions": instructions,
        "entrypoint": entry, "files": len(paths),
        "source_may_be_incomplete": incomplete,
        "source_sha256": (hashlib.sha256(source.read_bytes()).hexdigest()
                          if source.is_file() and source.stat().st_size <= 64*1024*1024
                          else None),
        "no_drm_or_payment_bypass": True,
    }
    (root / "asterion-runtime.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    return {"runtime": runtime, "runtime_plugin": plugin,
            "runtime_url": url, "runtime_bundle": str(root),
            "runtime_entrypoint": entry, "compatibility_prepared": True,
            "source_may_be_incomplete": incomplete,
            "standalone_apk": False}
