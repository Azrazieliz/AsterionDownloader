package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.URI
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Engine-specific Android routing without pretending desktop/console binaries are native APKs. */
object GamePortPlanner {
    enum class Engine(val label:String,val method:String) {
        HTML5("HTML5 visual novel","Play offline now; build an independent APK with GitHub Actions"),
        RPG_MAKER("RPG Maker MV/MZ","Play compatible HTML5 files; package the www directory as an APK"),
        RENPY("Ren'Py visual novel","Use Ren'Py SDK/RAPT when possible or JoiPlay's Ren'Py plugin without editable source"),
        RPG_MAKER_LEGACY("RPG Maker XP/VX/VX Ace","Import the complete distribution into JoiPlay with its RPG Maker plugin"),
        KIRIKIRI("Kirikiri","Export the complete game for a compatible Kirikiri Android interpreter"),
        NSCRIPTER("NScripter","Compatible Android interpreter required"),
        THREE_DS("Nintendo 3DS","Use Azahar on Android; 3DS game binaries are not native Android applications"),
        NDS("Nintendo DS","Use an Android-compatible Nintendo DS emulator"),
        WINDOWS("Windows executable","Run the complete Windows distribution in Winlator; not an Android-native port"),
        UNITY_WINDOWS("Packaged Windows Unity game","Use Winlator with the complete Windows build; native APK needs editable Unity source"),
        UNREAL_WINDOWS("Packaged Windows Unreal game","Use Winlator with the complete Windows build; native APK needs Unreal source"),
        GODOT_EXPORT("Packaged Godot game","Use a matching Godot Android runtime; a PCK file is not an editable project"),
        APK("Android application","Already an Android package: install through Android"),
        GODOT("Godot project","Export Android with Godot editor and installed export templates"),
        UNITY_PROJECT("Unity source project","Build Android using Unity editor and Android Build Support"),
        UNREAL_PROJECT("Unreal source project","Build Android through Unreal Automation Tool with installed SDK/NDK"),
        UNITY("Unity game","Rebuild from Unity source project for native Android"),
        UNREAL("Unreal Engine","Rebuild from Unreal project for native Android"),
        UNKNOWN("Unknown engine","Inspect original files and select a suitable Android runtime")
    }
    internal fun detect(paths:Collection<String>):Engine{
        val names=paths.map{it.lowercase().replace('\\','/')}
        if(names.any{it.endsWith("project.godot")})return Engine.GODOT
        if(names.any{it.endsWith(".uproject")})return Engine.UNREAL_PROJECT
        if(names.any{it.endsWith("projectsettings/projectversion.txt")} &&
            names.any{it.contains("/assets/") || it.startsWith("assets/")})
            return Engine.UNITY_PROJECT
        if(names.any{it.endsWith(".3ds")||it.endsWith(".cia")||it.endsWith(".cci")})
            return Engine.THREE_DS
        if(names.any{it.endsWith(".nds")})return Engine.NDS
        if(names.any{it.endsWith(".rpy")||it.endsWith(".rpa")||it.endsWith(".rpyc")})
            return Engine.RENPY
        if(names.any{it.endsWith(".rgssad")||it.endsWith(".rgss2a")||
            it.endsWith(".rgss3a")||it.endsWith(".rvdata")||
            it.endsWith(".rvdata2")||it.endsWith(".rxdata")})
            return Engine.RPG_MAKER_LEGACY
        if(names.any{it.endsWith(".xp3")})return Engine.KIRIKIRI
        if(names.any{it.endsWith(".nsa")||it.endsWith(".ns2")})return Engine.NSCRIPTER
        if(names.any{(it.startsWith("www/")||it.contains("/www/"))&&it.endsWith("index.html")} &&
            names.any{it.endsWith("rpg_core.js")||it.endsWith("rmmz_core.js")})
            return Engine.RPG_MAKER
        if(names.any{it.endsWith("index.html")})return Engine.HTML5
        if(names.any{it.endsWith(".apk")})return Engine.APK
        if(names.any{it.endsWith(".exe")||it.endsWith(".msi")}){
            if(names.any{it.endsWith("unityplayer.dll")||
                it.endsWith("_data/globalgamemanagers")})
                return Engine.UNITY_WINDOWS
            if(names.any{it.endsWith(".pak") && 
                (it.contains("/engine/")||it.contains("/content/paks/"))})
                return Engine.UNREAL_WINDOWS
            return Engine.WINDOWS
        }
        if(names.any{it.endsWith(".pck")})return Engine.GODOT_EXPORT
        if(names.any{it.endsWith(".unity3d")||it.endsWith(".assets")})return Engine.UNITY
        if(names.any{it.endsWith(".pak")})return Engine.UNREAL
        return Engine.UNKNOWN
    }
    fun inspect(a:Activity,source:Uri,name:String){
        val file=name.ifBlank{source.lastPathSegment.orEmpty()}
        if(!file.endsWith(".zip",true)){
            show(a,source,file,detect(listOf(file)))
            return
        }
        Toast.makeText(a,"Identifying game engine…",Toast.LENGTH_SHORT).show()
        thread(name="asterion-engine-identify"){
            val result=runCatching{
                val entries=ArrayList<String>()
                a.contentResolver.openInputStream(source)?.use{input->
                    ZipInputStream(BufferedInputStream(input)).use{zip->
                        // Bounded scan: identify early without extracting the full game.
                        // Engine markers can occur far beyond the first 120 ZIP
                        // entries in real multi-GB Windows game distributions.
                        while(entries.size<1600){
                            val e=zip.nextEntry?:break
                            if(!e.isDirectory)entries+=e.name.take(350)
                            if(entries.size>=1600)break
                        }
                    }
                }?:error("Unable to inspect selected game ZIP")
                detect(entries)
            }
            a.runOnUiThread{
                if(a.isFinishing || a.isDestroyed)return@runOnUiThread
                result.onSuccess{engine->show(a,source,file,engine)}
                    .onFailure{Toast.makeText(a,
                        "Game inspection failed: "+it.message,Toast.LENGTH_LONG).show()}
            }
        }
    }
    private fun show(a:Activity,source:Uri,name:String,engine:Engine){
        val options=when(engine){
            Engine.HTML5,Engine.RPG_MAKER->arrayOf("PLAY HTML5 ON PHONE",
                "PREPARE INDEPENDENT APK PORT","EXPORT GAME FILES","OPEN WITH ANDROID APP")
            Engine.RENPY->arrayOf("PREPARE REN'PY APK PORT",
                "EXPORT COMPLETE GAME FOR JOIPLAY","GET JOIPLAY")
            Engine.RPG_MAKER_LEGACY,Engine.GODOT_EXPORT->
                arrayOf("EXPORT COMPLETE GAME FOR JOIPLAY",
                    "GET JOIPLAY & MATCHING PLUGIN","SAVE COMPATIBILITY REPORT")
            Engine.WINDOWS,Engine.UNITY_WINDOWS,Engine.UNREAL_WINDOWS,
            Engine.UNITY,Engine.UNREAL->
                arrayOf("EXPORT COMPLETE GAME FOR WINLATOR",
                    "GET WINLATOR","SAVE COMPATIBILITY REPORT")
            Engine.KIRIKIRI,Engine.NSCRIPTER->
                arrayOf("EXPORT GAME FOR ENGINE INTERPRETER",
                    "OPEN WITH INSTALLED INTERPRETER","SAVE COMPATIBILITY REPORT")
            Engine.APK->arrayOf("INSTALL ANDROID APK")
            Engine.GODOT,Engine.UNITY_PROJECT,Engine.UNREAL_PROJECT->
                arrayOf("PREPARE ENGINE-SPECIFIC ANDROID BUILD",
                    "EXPORT ORIGINAL SOURCE","OPEN WITH COMPATIBLE APP")
            else->arrayOf("OPEN WITH INSTALLED RUNTIME","EXPORT GAME FILES",
                "SAVE ENGINE REPORT")
        }
        AlertDialog.Builder(a).setTitle(name)
            .setMessage(engine.label+"\n"+engine.method)
            .setItems(options){_,i->
                when(engine){
                    Engine.HTML5,Engine.RPG_MAKER->when(i){
                        0->a.startActivity(Intent(a,OfflineVnActivity::class.java).apply{
                            data=source;addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        })
                        1->saveRequest(a,name,engine)
                        2->PortableGameExporter.export(a,source,name)
                        else->external(a,source)
                    }
                    Engine.RENPY->when(i){
                        0->saveRequest(a,name,engine)
                        1->PortableGameExporter.export(a,source,name)
                        else->external(a,source)
                    }
                    Engine.RPG_MAKER_LEGACY,Engine.GODOT_EXPORT->when(i){
                        0->PortableGameExporter.export(a,source,name)
                        1->runtimeWebsite(a,"https://www.joiplay.net/")
                        else->saveRequest(a,name,engine)
                    }
                    Engine.WINDOWS,Engine.UNITY_WINDOWS,Engine.UNREAL_WINDOWS,
                    Engine.UNITY,Engine.UNREAL->when(i){
                        0->PortableGameExporter.export(a,source,name)
                        1->runtimeWebsite(a,"https://winlator.org/index.php")
                        else->saveRequest(a,name,engine)
                    }
                    Engine.KIRIKIRI,Engine.NSCRIPTER->when(i){
                        0->PortableGameExporter.export(a,source,name)
                        1->external(a,source)
                        else->saveRequest(a,name,engine)
                    }
                    Engine.APK->external(a,source,"application/vnd.android.package-archive")
                    Engine.GODOT,Engine.UNITY_PROJECT,Engine.UNREAL_PROJECT->when(i){
                        0->saveRequest(a,name,engine)
                        1->PortableGameExporter.export(a,source,name)
                        else->external(a,source)
                    }
                    else->when(i){
                        0->external(a,source)
                        1->{
                            if(name.endsWith(".zip",true))PortableGameExporter.export(a,source,name)
                            else saveRequest(a,name,engine)
                        }
                        else->saveRequest(a,name,engine)
                    }
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun runtimeWebsite(a:Activity,url:String){
        a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
    }
    private fun external(a:Activity,source:Uri,mime:String="*/*"){
        val intent=Intent(Intent.ACTION_VIEW).apply{
            setDataAndType(source,mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching{a.startActivity(Intent.createChooser(intent,"Choose game runtime"))}
            .onFailure{
                Toast.makeText(a,"No compatible Android application installed",
                    Toast.LENGTH_LONG).show()
            }
    }
    private fun saveRequest(a:Activity,name:String,engine:Engine){
        val cmd=when(engine){
            Engine.HTML5,Engine.RPG_MAKER->
                "python tools/package_game_android.py --input game.zip --out port --package com.asterion.portable.game"
            Engine.RENPY->
                "python tools/package_game_android.py --input game.zip --out port --renpy-sdk PATH_TO_RENPY_SDK"
            Engine.GODOT->
                "python tools/package_game_android.py --input game.zip --out port --godot-editor PATH_TO_GODOT"
            Engine.UNITY_PROJECT->
                "python tools/package_game_android.py --input game.zip --out port --unity-editor PATH_TO_UNITY"
            Engine.UNREAL_PROJECT->
                "python tools/package_game_android.py --input game.zip --out port --unreal-engine PATH_TO_UNREAL"
            Engine.RPG_MAKER_LEGACY,Engine.GODOT_EXPORT->
                "Prepare the complete game distribution for JoiPlay and its matching engine plugin"
            Engine.WINDOWS,Engine.UNITY_WINDOWS,Engine.UNREAL_WINDOWS,
            Engine.UNITY,Engine.UNREAL->
                "Prepare the complete Windows game for Winlator; this is not an APK build"
            Engine.KIRIKIRI,Engine.NSCRIPTER->
                "Import the original game files using a compatible Android interpreter"
            else->"Open this game in the compatible Android runtime; no generic binary-to-APK conversion exists"
        }
        val workflow=when(engine){
            Engine.HTML5,Engine.RPG_MAKER,Engine.GODOT,Engine.RENPY->
                ".github/workflows/game-port-github-only.yml"
            Engine.UNITY_PROJECT->".github/workflows/game-port-unity-github.yml"
            else->null
        }
        val json=JSONObject().apply{
            put("game",name);put("engine",engine.name)
            put("android_method",engine.method)
            // Keep the legacy field compatible with previously saved manifests.
            put("pc_build_command",cmd)
            put("github_build_workflow",workflow?:JSONObject.NULL)
            put("github_source_directory","game-ports/inbox/")
            put("github_source_release_supported",workflow!=null)
            put("github_inputs",JSONObject().apply{
                put("source_archive","game-ports/inbox/<game-source>.zip")
                put("source_release_tag","<GitHub-Release-tag-if-uploaded-from-phone>")
                put("source_asset_name","<game-source>.zip")
                put("package_id","com.asterion.portable.game")
                put("game_title",name.substringBeforeLast('.'))
                if(engine!=Engine.UNITY_PROJECT){
                    put("engine_mode",when(engine){
                        Engine.GODOT->"godot";Engine.RENPY->"renpy";else->"automatic"
                    })
                }
            })
            put("compatibility_runtime",when(engine){
                Engine.RPG_MAKER_LEGACY,Engine.RENPY,Engine.GODOT_EXPORT->"JoiPlay"
                Engine.WINDOWS,Engine.UNITY_WINDOWS,Engine.UNREAL_WINDOWS,
                Engine.UNITY,Engine.UNREAL->"Winlator"
                Engine.KIRIKIRI->"Kirikiri-compatible interpreter"
                Engine.NSCRIPTER->"ONScripter-compatible interpreter"
                else->JSONObject.NULL
            })
            put("native_apk_without_editable_source",
                engine in setOf(Engine.HTML5,Engine.RPG_MAKER))
            put("requires_original_game_zip",true)
            put("requires_unity_activation",engine==Engine.UNITY_PROJECT)
            put("unreal_source_requires_installed_engine",
                engine==Engine.UNREAL_PROJECT)
            put("github_action_currently_supports",
                listOf("HTML5","RPG_MAKER","GODOT","RENPY","UNITY_PROJECT"))
            put("android_build_status",when{
                workflow!=null->"GitHub workflow configured; submit complete editable source"
                engine==Engine.UNREAL_PROJECT->
                    "Needs authorized Unreal Android toolchain; not available on standard free GitHub runners"
                else->"Requires the compatible Android runtime or original source"
            })
        }.toString(2)
        val safe=name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"),"_")
            .take(48).ifBlank{"game"}
        thread(name="asterion-port-request"){
            val result=runCatching{
                val values=ContentValues().apply{
                    put(MediaStore.Downloads.DISPLAY_NAME,safe+"-android-port.json")
                    put(MediaStore.Downloads.MIME_TYPE,"application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS+"/AsterionDownloader/GamePortRequests/")
                    put(MediaStore.Downloads.IS_PENDING,1)
                }
                val uri=a.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    ?:error("Unable to create port request")
                try{
                    a.contentResolver.openOutputStream(uri,"w")!!.use{
                        it.write(json.toByteArray(Charsets.UTF_8))
                    }
                    a.contentResolver.update(uri,ContentValues().apply{
                        put(MediaStore.Downloads.IS_PENDING,0)
                    },null,null)
                }catch(e:Exception){a.contentResolver.delete(uri,null,null);throw e}
                uri
            }
            a.runOnUiThread{
                Toast.makeText(a,result.fold(
                    {"Port manifest saved in Downloads/AsterionDownloader/GamePortRequests"},
                    {"Port request failed: "+it.message}
                ),Toast.LENGTH_LONG).show()
            }
        }
    }
}
