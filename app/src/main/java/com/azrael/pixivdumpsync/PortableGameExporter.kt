package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.zip.ZipInputStream
import java.io.BufferedInputStream
import kotlin.concurrent.thread

/** Exports playable game assets to public Downloads for use in independent Android runtimes.
 * Does not claim to convert native Windows binaries into Android executables. */
object PortableGameExporter {
    private fun sanitize(s:String)=s.replace(Regex("[^a-zA-Z0-9_. -]"),"_")
        .trim().take(90).ifBlank{"game"}
    internal fun safeEntry(name:String):List<String>? {
        if(name.startsWith('/')||name.contains('\\')||name.contains(':'))return null
        val parts=name.split('/').filter{it.isNotBlank()}
        if(parts.isEmpty()||parts.any{it=="."||it==".."})return null
        return parts.map(::sanitize)
    }
    internal fun classify(paths:Set<String>):String=when(GamePortPlanner.detect(paths)){
        GamePortPlanner.Engine.RENPY->"Ren'Py: JoiPlay with its matching Ren'Py plugin; the complete game files are preserved"
        GamePortPlanner.Engine.RPG_MAKER_LEGACY->
            "RPG Maker XP/VX/VX Ace: import the complete game with the JoiPlay RPG Maker plugin"
        GamePortPlanner.Engine.KIRIKIRI->"Kirikiri: a compatible Android script interpreter is required"
        GamePortPlanner.Engine.NSCRIPTER->"NScripter: a compatible Android script interpreter is required"
        GamePortPlanner.Engine.RPG_MAKER,GamePortPlanner.Engine.HTML5->
            "HTML5: open the extracted index.html with a compatible Android web game runtime"
        GamePortPlanner.Engine.WINDOWS,GamePortPlanner.Engine.UNITY_WINDOWS,
        GamePortPlanner.Engine.UNREAL_WINDOWS,GamePortPlanner.Engine.UNITY,
        GamePortPlanner.Engine.UNREAL->
            "Windows executable and resources: run in Winlator. The files are not an Android-native port"
        GamePortPlanner.Engine.GODOT_EXPORT->
            "Exported Godot data: import with a matching Godot runtime; it is not an editable source project"
        else->"Unknown or console runtime: original files preserved; a compatible interpreter may be required"
    }
    private fun mime(name:String)=when(name.substringAfterLast('.',"").lowercase()){
        "html","htm"->"text/html"
        "js"->"application/javascript"
        "json"->"application/json"
        "png"->"image/png"
        "jpg","jpeg"->"image/jpeg"
        "webp"->"image/webp"
        "mp3"->"audio/mpeg"
        "ogg"->"audio/ogg"
        "mp4"->"video/mp4"
        "apk"->"application/vnd.android.package-archive"
        else->"application/octet-stream"
    }
    fun export(a:Activity,source:Uri,filename:String){
        val title=sanitize(filename.substringBeforeLast('.',filename)).take(55)
        val folder="Download/AsterionDownloader/Games/"+title+"_"+System.currentTimeMillis()+"/"
        val dialog=AlertDialog.Builder(a).setTitle("Exporting game outside Asterion")
            .setMessage("Preparing game files in Android Downloads…")
            .setCancelable(false).create()
        dialog.show()
        thread(name="asterion-game-export"){
            val files=linkedSetOf<String>()
            val created=ArrayList<Uri>()
            var size=0L
            var failure:Throwable?=null
            try{
                val input=a.contentResolver.openInputStream(source)
                    ?:error("Cannot open selected game archive")
                BufferedInputStream(input).use{stream->
                    if(!filename.endsWith(".zip",true)){
                        // A single EXE/RPA/XP3 is still a file, not a ZIP.
                        // Copy it intact rather than feeding non-ZIP bytes to
                        // ZipInputStream and returning an empty-export error.
                        val leaf=sanitize(filename)
                        require(files.add(leaf.lowercase())){"Duplicate game file"}
                        val output=ContentValues().apply{
                            put(MediaStore.Downloads.DISPLAY_NAME,leaf)
                            put(MediaStore.Downloads.MIME_TYPE,mime(leaf))
                            put(MediaStore.Downloads.RELATIVE_PATH,folder)
                            put(MediaStore.Downloads.IS_PENDING,1)
                        }
                        val uri=a.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI,output)
                            ?:error("Failed creating game file")
                        created.add(uri)
                        val buf=ByteArray(65536)
                        a.contentResolver.openOutputStream(uri,"w")?.use{out->
                            while(true){
                                val n=stream.read(buf)
                                if(n<0)break
                                size+=n
                                require(size<=8L*1024*1024*1024){"Game file exceeds 8 GB"}
                                out.write(buf,0,n)
                            }
                        }?:error("Failed writing game file")
                        a.contentResolver.update(uri,ContentValues().apply{
                            put(MediaStore.Downloads.IS_PENDING,0)
                        },null,null)
                    }else ZipInputStream(stream).use{zip->
                        val buf=ByteArray(65536)
                        while(true){
                            val entry=zip.nextEntry?:break
                            if(entry.isDirectory){zip.closeEntry();continue}
                            val parts=safeEntry(entry.name)?:error("Unsafe archive file path")
                            require(files.size<25000){"Game archive exceeds 25,000 files"}
                            val relative=parts.joinToString("/").lowercase()
                            require(files.add(relative)){"Duplicate game file: "+relative}
                            val subdir=parts.dropLast(1).joinToString("/")
                            val output=ContentValues().apply{
                                put(MediaStore.Downloads.DISPLAY_NAME,parts.last())
                                put(MediaStore.Downloads.MIME_TYPE,mime(parts.last()))
                                put(MediaStore.Downloads.RELATIVE_PATH,folder+
                                    if(subdir.isEmpty())"" else subdir+"/")
                                put(MediaStore.Downloads.IS_PENDING,1)
                            }
                            val uri=a.contentResolver.insert(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,output)
                                ?:error("Failed creating game file")
                            created.add(uri)
                            a.contentResolver.openOutputStream(uri,"w")?.use{out->
                                while(true){
                                    val n=zip.read(buf)
                                    if(n<0)break
                                    size+=n
                                    require(size<=8L*1024*1024*1024){"Extracted game exceeds 8 GB"}
                                    out.write(buf,0,n)
                                }
                            }?:error("Failed writing game file")
                            a.contentResolver.update(uri,ContentValues().apply{
                                put(MediaStore.Downloads.IS_PENDING,0)
                            },null,null)
                            zip.closeEntry()
                            if(files.size%50==0)a.runOnUiThread {
                                dialog.setMessage("Exported "+files.size+" game files")
                            }
                        }
                    }
                }
                require(files.isNotEmpty()){"Archive is empty"}
            }catch(t:Throwable){failure=t}
            if(failure!=null)
                for(uri in created)runCatching{a.contentResolver.delete(uri,null,null)}
            a.runOnUiThread{
                dialog.dismiss()
                if(failure!=null){
                    AlertDialog.Builder(a).setTitle("Game export failed")
                        .setMessage(failure?.message).setPositiveButton("Close",null).show()
                }else{
                    val engine=GamePortPlanner.detect(files)
                    val runtime=when(engine){
                        GamePortPlanner.Engine.WINDOWS,
                        GamePortPlanner.Engine.UNITY_WINDOWS,
                        GamePortPlanner.Engine.UNREAL_WINDOWS,
                        GamePortPlanner.Engine.UNITY,
                        GamePortPlanner.Engine.UNREAL->
                            "Winlator" to "https://winlator.org/index.php"
                        GamePortPlanner.Engine.RENPY,
                        GamePortPlanner.Engine.RPG_MAKER_LEGACY,
                        GamePortPlanner.Engine.GODOT_EXPORT,
                        GamePortPlanner.Engine.HTML5,
                        GamePortPlanner.Engine.RPG_MAKER->
                            "JoiPlay" to "https://www.joiplay.net/"
                        else->null
                    }
                    val notice=AlertDialog.Builder(a).setTitle("Game exported")
                        .setMessage("Files: "+files.size+"\n"+folder+"\n\n"+
                            classify(files)+"\n\nNo claim of a native APK conversion."+
                            if(files.size==1)"\nA single game file may require additional original assets and DLLs." else "")
                    if(runtime!=null)notice.setPositiveButton("GET "+runtime.first.uppercase()){_,_->
                        a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(runtime.second)))
                    }
                    notice.setNegativeButton("Done",null).show()
                }
            }
        }
    }
}
