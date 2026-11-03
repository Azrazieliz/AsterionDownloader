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
        with ZipFile(root/"traversal.zip","w") as z:
            z.writestr("../bad.txt","unsafe")
            z.writestr("index.html","safe")
        try:
            package(root/"traversal.zip",root/"unsafe","com.asterion.portable.bad","Bad",build=False)
        except ValueError:pass
        else:raise AssertionError("Traversal archive accepted")
    print("ENGINE_ANDROID_PORT_TESTS_PASS")

if __name__=="__main__":run()
