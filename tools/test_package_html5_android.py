from pathlib import Path
from tempfile import TemporaryDirectory
from zipfile import ZipFile
from package_html5_android import build

def run():
    with TemporaryDirectory() as temp:
        root=Path(temp)
        source=root/"game.zip"
        with ZipFile(source,"w") as z:
            z.writestr("game/index.html","<button>Play</button><script src='main.js'></script>")
            z.writestr("game/main.js","document.title='Game';")
        out=root/"output"
        assert build(source,out,"com.asterion.portable.test","Playable VN")==2
        assert (out/"app/src/main/assets/game/index.html").is_file()
        assert (out/"app/src/main/assets/game/main.js").is_file()
        manifest=(out/"app/src/main/AndroidManifest.xml").read_text()
        assert "GameActivity" in manifest and "Playable VN" in manifest
        bad=root/"bad.zip"
        with ZipFile(bad,"w") as z:
            z.writestr("../escape.txt","x")
            z.writestr("index.html","<h1>x</h1>")
        try:build(bad,root/"badoutput","com.asterion.portable.bad","Bad")
        except ValueError:pass
        else:raise AssertionError("Unsafe ZIP accepted")
        print("HTML5_PACKAGE_TESTS_PASS")

if __name__=="__main__":run()
