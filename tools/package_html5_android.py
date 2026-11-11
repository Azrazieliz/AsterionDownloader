#!/usr/bin/env python3
"""Build an independent Android project from an HTML5 game ZIP.
Run: python tools/package_html5_android.py --zip game.zip --out D:/build-game
Then: gradle -p D:/build-game assembleDebug
"""
import argparse
import html
import re
import stat
import zipfile
from pathlib import Path, PurePosixPath

JAVA = r"""
package GAME_PACKAGE;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import java.io.*;
import java.util.Locale;

public class GameActivity extends Activity {
    private WebView browser;
    private static final String HOST="game.asterion.local";
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(1024, 1024);
        browser=new WebView(this);
        setContentView(browser);
        WebSettings settings=browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        browser.setWebChromeClient(new WebChromeClient());
        browser.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u=req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String p=Uri.decode(u.getEncodedPath());
                if (p == null || p.indexOf("..")>=0 || p.indexOf("\\")>=0) return missing();
                if (p.equals("/")) p="/index.html";
                try {
                    return new WebResourceResponse(mime(p),"UTF-8",getAssets().open("game"+p));
                } catch(Exception ignored) { return missing(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req) {
                Uri u=req.getUrl();
                if (HOST.equals(u.getHost())) return false;
                if ("https".equals(u.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,u)); } catch(Exception ignored) {}
                }
                return true;
            }
        });
        browser.loadUrl("https://"+HOST+"/index.html");
    }
    static WebResourceResponse missing(){
        return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
    }
    static String mime(String path){
        String p=path.toLowerCase(Locale.ROOT);
        if (p.endsWith(".html")||p.endsWith(".htm")) return "text/html";
        if (p.endsWith(".js")||p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg")||p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".mp3")) return "audio/mpeg";
        if (p.endsWith(".ogg")) return "audio/ogg";
        if (p.endsWith(".mp4")) return "video/mp4";
        if (p.endsWith(".woff2")) return "font/woff2";
        return "application/octet-stream";
    }
    @Override public void onBackPressed(){
        if(browser.canGoBack())browser.goBack();else super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(browser!=null){browser.destroy();browser=null;}super.onDestroy();
    }
}
"""

def build(archive:Path, out:Path, appid:str, label:str):
    if not re.fullmatch(r'[a-z][a-z0-9_]*(\.[a-z][a-z0-9_]*)+',appid):
        raise ValueError("Invalid Android app ID")
    if out.exists() and any(out.iterdir()):
        raise ValueError("Output directory is not empty; existing work preserved")
    with zipfile.ZipFile(archive) as z:
        entries=[e for e in z.infolist() if not e.is_dir() and not e.filename.startswith("__MACOSX/")]
        if not 1<=len(entries)<=25000: raise ValueError("Invalid game file count")
        if sum(e.file_size for e in entries)>2_000_000_000:
            raise ValueError("Standalone APK game exceeds 2 GB")
        seen=set()
        for e in entries:
            p=PurePosixPath(e.filename)
            if p.is_absolute() or '\\' in e.filename or ':' in e.filename or \
                 any(c in ('..','.') for c in p.parts) or stat.S_ISLNK(e.external_attr>>16):
                raise ValueError("Unsafe ZIP entry: "+e.filename)
            if p.as_posix().lower() in seen: raise ValueError("Duplicate path: "+e.filename)
            seen.add(p.as_posix().lower())
        candidates=[PurePosixPath(e.filename) for e in entries
                    if PurePosixPath(e.filename).name.lower()=='index.html']
        if not candidates: raise ValueError("HTML5 game must contain index.html")
        prefix=min(candidates,key=lambda p:len(p.parts)).parent
        if any(not PurePosixPath(e.filename).is_relative_to(prefix) for e in entries):
            raise ValueError("Game has unrelated files outside its index.html folder")
        assets=out/'app/src/main/assets/game'
        assets.mkdir(parents=True)
        for e in entries:
            relative=PurePosixPath(e.filename).relative_to(prefix)
            dest=assets.joinpath(*relative.parts)
            dest.parent.mkdir(parents=True,exist_ok=True)
            with z.open(e) as src,dest.open('wb') as target:
                while chunk:=src.read(131072):target.write(chunk)
    (out/'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories { google(); mavenCentral() } }
rootProject.name="PortableHtml5Game"
include(":app")
''',encoding='utf-8')
    (out/'build.gradle.kts').write_text('plugins { id("com.android.application") version "8.7.3" apply false }\n',encoding='utf-8')
    (out/'app/build.gradle.kts').write_text(f'''plugins {{ id("com.android.application") }}
android {{
 namespace="{appid}"
 compileSdk=35
 defaultConfig {{ applicationId="{appid}"; minSdk=29; targetSdk=35; versionCode=1; versionName="1.0" }}
 compileOptions {{ sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }}
}}
''',encoding='utf-8')
    code=out/'app/src/main/java'/Path(*appid.split('.'))
    code.mkdir(parents=True)
    (code/'GameActivity.java').write_text(JAVA.replace('GAME_PACKAGE',appid),encoding='utf-8')
    (out/'app/src/main/AndroidManifest.xml').write_text(f'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<application android:label="{html.escape(label,quote=True)}"
 android:theme="@android:style/Theme.Material.Light.NoActionBar" android:usesCleartextTraffic="false">
<activity android:name=".GameActivity" android:exported="true">
<intent-filter><action android:name="android.intent.action.MAIN"/>
<category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>
''',encoding='utf-8')
    print(f"Prepared {len(entries)} files at {out}. Run gradle -p {out} assembleDebug")
    return len(entries)

if __name__=='__main__':
    cli=argparse.ArgumentParser()
    cli.add_argument('--zip',type=Path,required=True)
    cli.add_argument('--out',type=Path,required=True)
    cli.add_argument('--package',default='com.asterion.portable.htmlgame')
    cli.add_argument('--label',default='HTML5 Visual Novel')
    a=cli.parse_args()
    build(a.zip,a.out,a.package,a.label)
