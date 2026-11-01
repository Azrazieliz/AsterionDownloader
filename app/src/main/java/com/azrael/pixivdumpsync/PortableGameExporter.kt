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
    internal fun classify(paths:Set<String>):String=when {
        paths.any{it.endsWith(".rpa")||it.endsWith(".rpy")||it.endsWith(".rpyc")} ->
            "Ren'Py game: use JoiPlay with the Ren'Py plugin, or package its sources with RAPT"
        paths.any{it.endsWith(".xp3")} ->
            "Kirikiri: a compatible external Android runtime is needed"
        paths.any{it.endsWith(".nsa")||it.endsWith(".ns2")} ->
            "NScripter: a compatible external Android runtime is needed"
        paths.any{it.endsWith("index.html")} ->
            "HTML5 game: load the exported folder into JoiPlay or a compatible HTML game player"
        paths.any{it.endsWith(".exe")} ->
            "Windows executable: not automatically convertible; needs a compatible runtime or port"
        else -> "Unknown game engine: exported files are available to other Android applications"
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
            .setMessage("Extracting to the Android Downloads folder…")
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
                    ZipInputStream(stream).use{zip->
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
                    AlertDialog.Builder(a).setTitle("Game exported")
                        .setMessage("Files: "+files.size+"\n"+folder+"\n\n"+
                            classify(files)+"\n\nYou can open this folder from another Android application.")
                        .setPositiveButton("Get JoiPlay"){_,_->
                            a.startActivity(Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://www.joiplay.net/")))
                        }.setNegativeButton("Done",null).show()
                }
            }
        }
    }
}
