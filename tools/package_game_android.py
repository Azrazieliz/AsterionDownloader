#!/usr/bin/env python3
"""Deterministic Android game port builder, extending the existing HTML5 APK packager.

Examples:
 python tools/package_game_android.py --input game.zip --out D:/ports/mygame --package com.example.mygame --label "My Game"
 python tools/package_game_android.py --input renpy.zip --renpy-sdk D:/RenPy/renpy-8.5.0-sdk --out D:/ports/renpy
3DS/Windows binaries are classified for a runtime, not silently relabeled as Android APKs.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import stat
import subprocess
import sys
from tempfile import TemporaryDirectory
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED
from package_html5_android import build as build_html5

def safe_members(z:ZipFile):
    members=[e for e in z.infolist() if not e.is_dir() and not e.filename.startswith("__MACOSX/")]
    if not 1<=len(members)<=25000:raise ValueError("Archive file count out of range")
    if sum(e.file_size for e in members)>2_000_000_000:
        raise ValueError("Portable game exceeds 2 GB")
    seen=set()
    for e in members:
        path=PurePosixPath(e.filename)
        if path.is_absolute() or '\\' in e.filename or ':' in e.filename or \
           any(p in ('..','.') for p in path.parts) or stat.S_ISLNK(e.external_attr>>16):
            raise ValueError("Unsafe ZIP member: "+e.filename)
        key=path.as_posix().lower()
        if key in seen:raise ValueError("Duplicate ZIP file: "+e.filename)
        seen.add(key)
    return members

def detect(paths:list[str])->str:
    lower=[p.lower().replace('\\','/') for p in paths]
    names=set(lower)
    if any(p.endswith((".3ds",".cia",".cci")) for p in lower):return "NINTENDO_3DS"
    if any(p.endswith(".nds") for p in lower):return "NINTENDO_DS"
    if any(p.endswith(".rpy") or p.endswith(".rpa") for p in lower):return "RENPY"
    if any(p.endswith(".xp3") for p in lower):return "KIRIKIRI"
    if any(p.endswith((".nsa",".ns2")) for p in lower):return "NSCRIPTER"
    if any("www/index.html" in p for p in lower) and any(
        p.endswith("rpg_core.js") or p.endswith("rmmz_core.js") for p in lower):
        return "RPG_MAKER_HTML"
    if any(p.endswith("index.html") for p in lower):return "HTML5"
    if any(p.endswith((".exe",".msi")) for p in lower):return "WINDOWS"
    if any(p.endswith((".apk",".aab")) for p in lower):return "ANDROID"
    if any(p.endswith((".unity3d",".assets")) for p in lower):return "UNITY"
    if any(p.endswith(".pak") for p in lower):return "UNREAL_OR_OTHER"
    return "UNKNOWN"

def sdk_command(sdk:Path)->list[str]:
    if sys.platform=="win32":
        python=sdk/"lib/py3-windows-x86_64/python.exe"
        if not python.exists():raise FileNotFoundError("Ren'Py SDK bundled Python was not found")
        return [str(python),str(sdk/"renpy.py")]
    command=sdk/"renpy.sh"
    if not command.is_file():raise FileNotFoundError("renpy.sh was not found")
    return [str(command)]

def export_selected(z:ZipFile,members:list[ZipInfo],dest:Path):
    dest.mkdir(parents=True,exist_ok=True)
    for member in members:
        p=PurePosixPath(member.filename)
        target=dest.joinpath(*p.parts)
        target.parent.mkdir(parents=True,exist_ok=True)
        with z.open(member) as stream,target.open("wb") as out:
            shutil.copyfileobj(stream,out,1024*128)

def package(source:Path,out:Path,package_id:str,label:str,renpy_sdk:Path|None=None,
            gradle:str="gradle",build:bool=True)->dict:
    if not source.is_file():raise FileNotFoundError(source)
    if out.exists() and any(out.iterdir()):
        raise ValueError("Output directory must be empty; existing project is preserved")
    paths=[]
    if source.suffix.lower()==".zip":
        with ZipFile(source) as z:
            members=safe_members(z);paths=[m.filename for m in members]
    else:paths=[source.name]
    engine=detect(paths)
    out.mkdir(parents=True,exist_ok=True)
    result={"engine":engine,"input":str(source.resolve()),"output":str(out.resolve()),
        "standalone_apk":False,"runtime":None,"project":None,"files":len(paths)}
    if engine in ("HTML5","RPG_MAKER_HTML"):
        if source.suffix.lower()!=".zip":raise ValueError("HTML5 requires a complete ZIP")
        zip_path=source
        if engine=="RPG_MAKER_HTML":
            with ZipFile(source) as z:
                members=safe_members(z)
                selected=[m for m in members if "/www/" in "/"+m.filename.lower()]
                if not selected:raise ValueError("RPG Maker www assets are missing")
                # Exclude the Windows .exe outside www; it is not part of the Android port.
                temp=out/"game-web-only.zip"
                with ZipFile(temp,'w',ZIP_DEFLATED) as target:
                    for m in selected:target.writestr(m.filename,z.read(m))
            zip_path=temp
        project=out/"android-project"
        build_html5(zip_path,project,package_id,label)
        result["project"]=str(project)
        if build:
            subprocess.run([gradle,"-p",str(project),"assembleDebug","--no-daemon"],check=True)
            apk=project/"app/build/outputs/apk/debug/app-debug.apk"
            if not apk.is_file():raise RuntimeError("Gradle returned no standalone APK")
            dest=out/(package_id.rsplit('.',1)[-1]+"-standalone.apk")
            shutil.copy2(apk,dest)
            result["standalone_apk"]=True
            result["apk"]=str(dest)
            result["sha256"]=hashlib.sha256(dest.read_bytes()).hexdigest()
        else:result["build_command"]=f'{gradle} -p "{project}" assembleDebug'
    elif engine=="RENPY":
        if not renpy_sdk:
            result["runtime"]="JoiPlay with Ren'Py plugin; supply --renpy-sdk for a standalone APK"
        elif source.suffix.lower()!=".zip":
            raise ValueError("Ren'Py must be supplied as a complete project ZIP")
        else:
            project=out/"renpy-project"
            with ZipFile(source) as z:
                selected=safe_members(z)
                export_selected(z,selected,project)
            # Collapse one archive wrapper folder when it contains the actual game directory.
            if not (project/"game").is_dir():
                children=[d for d in project.iterdir() if d.is_dir()]
                if len(children)==1 and (children[0]/"game").is_dir():
                    project=children[0]
            if not (project/"game").is_dir():raise ValueError("Ren'Py game/ directory missing")
            result["project"]=str(project)
            command=sdk_command(renpy_sdk)+[
                "launcher","android_build",str(project),"--destination",str(out/"apk"),"--package","android"]
            result["build_command"]=command
            if build:
                subprocess.run(command,cwd=str(renpy_sdk),check=True)
                apks=list((out/"apk").rglob("*.apk"))
                if not apks:raise RuntimeError("Ren'Py built no APK; configure SDK, RAPT and signing")
                result["standalone_apk"]=True
                result["apk"]=str(apks[0])
                result["sha256"]=hashlib.sha256(apks[0].read_bytes()).hexdigest()
    else:
        result["runtime"]={
            "NINTENDO_3DS":"Azahar Android emulator; 3DS binaries cannot be transformed into an Android-native APK",
            "NINTENDO_DS":"Nintendo DS compatible Android emulator",
            "WINDOWS":"Winlator or an Android-compatible Windows runtime; Windows EXEs are not native Android APKs",
            "KIRIKIRI":"Compatible Kirikiri Android interpreter",
            "NSCRIPTER":"Compatible NScripter Android interpreter",
            "UNITY":"Requires a Unity Android rebuild with its project/source assets",
            "UNREAL_OR_OTHER":"Requires an engine-specific Android build from editable project/source",
            "ANDROID":"This is already an Android package; inspect and install it rather than converting",
            "UNKNOWN":"Source/game engine could not be identified safely"
        }.get(engine,"No safe native converter available")
    (out/"port-report.json").write_text(json.dumps(result,indent=2,ensure_ascii=False),encoding="utf-8")
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--input",type=Path,required=True)
    p.add_argument("--out",type=Path,required=True)
    p.add_argument("--package",default="com.asterion.portable.visualnovel")
    p.add_argument("--label",default="Portable Visual Novel")
    p.add_argument("--renpy-sdk",type=Path)
    p.add_argument("--gradle",default="gradle")
    p.add_argument("--prepare-only",action="store_true")
    a=p.parse_args()
    result=package(a.input,a.out,a.package,a.label,a.renpy_sdk,a.gradle,not a.prepare_only)
    print(json.dumps(result,indent=2,ensure_ascii=False))
if __name__=="__main__":main()
