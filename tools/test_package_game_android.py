from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile
from package_game_android import package, detect

def run():
    assert detect(["game/script.rpy","game/archive.rpa"])=="RENPY"
    assert detect(["roms/example.3ds"])=="NINTENDO_3DS"
    assert detect(["Game.exe"])=="WINDOWS"
    assert detect(["game/www/index.html","game/www/js/rpg_core.js"])=="RPG_MAKER_HTML"
    assert detect(["mygame/index.html","mygame/main.js"])=="HTML5"
    with TemporaryDirectory() as temp:
        root=Path(temp)
        source=root/"game.zip"
        with ZipFile(source,"w") as z:
            z.writestr("HTML5/index.html","<button>Play</button>")
            z.writestr("HTML5/game.js","console.log('play')")
        report=package(source,root/"output","com.asterion.portable.testing","Asterion Test",
            build=False)
        assert report["engine"]=="HTML5"
        assert (root/"output/android-project/app/src/main/assets/game/index.html").is_file()
        assert (root/"output/port-report.json").exists()
        with ZipFile(root/"renpy.zip","w") as z:
            z.writestr("Novel/game/script.rpy","label start:\n    'Hello'\n")
        report=package(root/"renpy.zip",root/"renpy-out",
            "com.asterion.portable.renpytest","RenPy",build=False)
        assert report["engine"]=="RENPY"
        assert "JoiPlay" in report["runtime"]
        with ZipFile(root/"rpg.zip","w") as z:
            z.writestr("Game.exe","not game code")
            z.writestr("www/index.html","<!doctype html>")
            z.writestr("www/js/rpg_core.js","test")
        report=package(root/"rpg.zip",root/"rpg-out",
            "com.asterion.portable.rpgtest","RPG",build=False)
        assert report["engine"]=="RPG_MAKER_HTML"
        assert (root/"rpg-out/android-project/app/src/main/assets/game/js/rpg_core.js").exists()
        # Actual editable-engine projects are routed to their real Android exporters.
        godot=root/"godot-source"
        godot.mkdir()
        (godot/"project.godot").write_text("[application]\nconfig/name=\"Test\"\n")
        (godot/"export_presets.cfg").write_text(
            '[preset.0]\nname="Android"\nplatform="Android"\nrunnable=true\n')
        report=package(godot,root/"godot-out",
            "com.asterion.portable.godot","Godot",build=False)
        assert report["engine"]=="GODOT_PROJECT"
        assert "--export-debug" in report["build_command"]
        assert report["standalone_apk"] is False
        assert Path(report["project"]).is_relative_to(root/"godot-out")
        assert Path(report["project"]) != godot
        assert (godot/"project.godot").read_text().startswith("[application]")
        unity=root/"unity-source"
        (unity/"Assets").mkdir(parents=True)
        (unity/"ProjectSettings").mkdir()
        (unity/"ProjectSettings/ProjectVersion.txt").write_text("m_EditorVersion: 2022.3.0f1")
        report=package(unity,root/"unity-out",
            "com.asterion.portable.unity","Unity",build=False)
        assert report["engine"]=="UNITY_PROJECT"
        assert (root/"unity-out/unity-android-copy/Assets/Editor/AsterionAndroidBuild.cs").is_file()
        assert (unity/"Assets/Editor/AsterionAndroidBuild.cs").exists() is False, \
            "Original Unity project was modified"
        assert "-executeMethod" in report["build_command"]
        unreal=root/"unreal-source"
        unreal.mkdir()
        (unreal/"Game.uproject").write_text('{"FileVersion":3}')
        report=package(unreal,root/"unreal-out",
            "com.asterion.portable.unreal","Unreal",build=False)
        assert report["engine"]=="UNREAL_PROJECT"
        assert "BuildCookRun" in report["build_command"]
        assert Path(report["project"]).is_relative_to(root/"unreal-out")
        assert Path(report["project"]) != unreal
        assert (unreal/"Game.uproject").read_text()=='{"FileVersion":3}'
        with ZipFile(root/"traversal.zip","w") as z:
            z.writestr("../bad.txt","unsafe")
            z.writestr("index.html","safe")
        try:
            package(root/"traversal.zip",root/"unsafe","com.asterion.portable.bad","Bad",build=False)
        except ValueError:pass
        else:raise AssertionError("Traversal archive accepted")
    print("ENGINE_ANDROID_PORT_TESTS_PASS")

if __name__=="__main__":run()
