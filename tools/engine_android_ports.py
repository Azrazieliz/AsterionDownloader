#!/usr/bin/env python3
"""Source-project Android exports. Does not convert proprietary Windows/console binaries.

All outputs are isolated; original directories and source archives remain unchanged.
Uses installed engine editors and their Android export SDK/toolchains.
"""
from pathlib import Path
from zipfile import ZipFile
import hashlib
import os
import shutil
import subprocess

def identify(paths):
    names=[x.lower().replace("\\","/") for x in paths]
    if any(x.endswith("project.godot") for x in names): return "GODOT_PROJECT"
    if any(x.endswith(".uproject") for x in names): return "UNREAL_PROJECT"
    if any(x.endswith("projectsettings/projectversion.txt") for x in names) and any(
        x=="assets" or x.startswith("assets/") or "/assets/" in x for x in names
    ): return "UNITY_PROJECT"
    return None

def project_root(directory,engine):
    p=Path(directory)
    checker={
        "GODOT_PROJECT":lambda q:(q/"project.godot").is_file(),
        "UNITY_PROJECT":lambda q:(q/"Assets").is_dir() and
            (q/"ProjectSettings/ProjectVersion.txt").is_file(),
        "UNREAL_PROJECT":lambda q:bool(list(q.glob("*.uproject")))
    }[engine]
    if checker(p):return p
    candidates=[q for q in p.iterdir() if q.is_dir() and checker(q)]
    if len(candidates)==1:return candidates[0]
    raise ValueError(engine+" source project root cannot be identified")

UNITY_BUILD_SCRIPT=r'''
using System;
using System.Linq;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEngine;

namespace Asterion {
    public static class AndroidBuild {
        public static void Build() {
            var scenes=EditorBuildSettings.scenes.Where(s=>s.enabled)
                .Select(s=>s.path).ToArray();
            if(scenes.Length==0)
                throw new Exception("No enabled scenes in Unity Build Settings");
            var package=Environment.GetEnvironmentVariable("ASTERION_ANDROID_PACKAGE");
            var label=Environment.GetEnvironmentVariable("ASTERION_ANDROID_LABEL");
            var output=Environment.GetEnvironmentVariable("ASTERION_ANDROID_OUTPUT");
            if(String.IsNullOrWhiteSpace(output))throw new Exception("Output APK path missing");
            PlayerSettings.applicationIdentifier=package;
            PlayerSettings.productName=label;
            EditorUserBuildSettings.buildAppBundle=false;
            var options=new BuildPlayerOptions {
                scenes=scenes,locationPathName=output,target=BuildTarget.Android,
                options=BuildOptions.None
            };
            var report=BuildPipeline.BuildPlayer(options);
            if(report.summary.result!=BuildResult.Succeeded)
                throw new Exception("Unity Android build failed: "+report.summary.result);
        }
    }
}
'''

def prepare(source,output,engine,package_id,label,godot_editor=None,
            unity_editor=None,unreal_engine=None):
    out=Path(output).resolve()
    out.mkdir(parents=True,exist_ok=True)
    source=Path(source).resolve()
    # Engine editors write imports/caches during builds. Never point Godot or UAT
    # at the original project: stage a separate project first, just like Unity.
    if engine in ("GODOT_PROJECT","UNREAL_PROJECT"):
        root=project_root(source,engine)
        if not root.is_relative_to(out):
            stage=out/("godot-android-copy" if engine=="GODOT_PROJECT"
                       else "unreal-android-copy")
            if stage.exists():raise ValueError("Native staging exists; no overwrite")
            ignored=("Library","Temp","Logs","obj",".git",".gradle",".godot",
                     "DerivedDataCache","Intermediate","Saved","Binaries")
            shutil.copytree(root,stage,ignore=shutil.ignore_patterns(*ignored))
            source=stage
        else:
            source=root
    if engine=="UNITY_PROJECT":
        project_dir=out/"unity-android-copy"
        if project_dir.exists():raise ValueError("Unity staging already exists; no overwrite")
        shutil.copytree(source,project_dir,ignore=shutil.ignore_patterns("Library",
            "Temp","Logs","obj",".git",".gradle","Builds"))
        project=project_root(project_dir,engine)
        editor_script=project/"Assets/Editor/AsterionAndroidBuild.cs"
        if editor_script.exists():
            raise ValueError("Existing AsterionAndroidBuild.cs — refusing overwrite")
        editor_script.parent.mkdir(parents=True,exist_ok=True)
        editor_script.write_text(UNITY_BUILD_SCRIPT,encoding="utf-8")
        editor=Path(unity_editor).resolve() if unity_editor else None
        if editor and not editor.is_file():raise FileNotFoundError(editor)
        apk=out/"unity-standalone.apk"
        command=[str(editor or "<path-to-Unity-editor>"),"-batchmode","-nographics",
                 "-quit","-projectPath",str(project),"-executeMethod",
                 "Asterion.AndroidBuild.Build","-logFile","-"]
        environment={"ASTERION_ANDROID_PACKAGE":package_id,
                     "ASTERION_ANDROID_LABEL":label,
                     "ASTERION_ANDROID_OUTPUT":str(apk)}
        return {"project":str(project),"apk":str(apk),"build_command":command,
                "build_env":environment,"ready":bool(editor)}
    if engine=="GODOT_PROJECT":
        project=project_root(source,engine)
        preset=project/"export_presets.cfg"
        if not preset.is_file(): raise ValueError(
            "Godot Android export preset is missing. Configure Android export "+
            "and install export templates in the Godot editor first.")
        cfg=preset.read_text(encoding="utf-8-sig",errors="replace")
        if 'name="Android"' not in cfg and 'name="Android (Runnable)"' not in cfg:
            raise ValueError("A Godot Android export preset must be configured")
        name="Android" if 'name="Android"' in cfg else "Android (Runnable)"
        editor=Path(godot_editor).resolve() if godot_editor else None
        if editor and not editor.is_file():raise FileNotFoundError(editor)
        apk=out/"godot-standalone.apk"
        command=[str(editor or "<path-to-Godot-editor>"),"--headless",
                 "--path",str(project),"--export-debug",name,str(apk)]
        return {"project":str(project),"apk":str(apk),"build_command":command,
                "build_env":{},"ready":bool(editor)}
    if engine=="UNREAL_PROJECT":
        project=project_root(source,engine)
        files=list(project.glob("*.uproject"))
        if len(files)!=1:raise ValueError("Expected exactly one .uproject file")
        engine_root=Path(unreal_engine).resolve() if unreal_engine else None
        uat=engine_root/"Engine/Build/BatchFiles"/(
            "RunUAT.bat" if os.name=="nt" else "RunUAT.sh") if engine_root else None
        if uat and not uat.is_file():raise FileNotFoundError(uat)
        command=[str(uat or "<path-to-RunUAT>"),"BuildCookRun",
                 "-project="+str(files[0]),"-platform=Android",
                 "-clientconfig=Development","-build","-cook","-stage",
                 "-package","-archive","-archivedirectory="+str(out),
                 "-unattended","-nop4"]
        return {"project":str(project),"apk":None,"build_command":command,
                "build_env":{},"ready":bool(uat)}
    raise ValueError("Unsupported native engine: "+engine)

def run_plan(plan):
    if not plan["ready"]:
        raise RuntimeError("Missing installed engine editor/Android SDK/export template")
    env=os.environ.copy()
    env.update(plan["build_env"])
    subprocess.run(plan["build_command"],env=env,check=True,cwd=plan["project"])
    target=Path(plan["apk"]) if plan.get("apk") else None
    if target and not target.is_file():
        raise RuntimeError("Engine finished without a standalone APK")
    if not target:
        candidates=list(Path(plan["build_command"][-3].split("=",1)[-1]).rglob("*.apk"))
        if not candidates:
            raise RuntimeError("Unreal UAT finished without archived APK")
        target=candidates[0]
    return {"standalone_apk":True,"apk":str(target),
            "sha256":hashlib.sha256(target.read_bytes()).hexdigest()}
