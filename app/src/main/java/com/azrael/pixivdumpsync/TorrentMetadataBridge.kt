package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.security.MessageDigest
import java.lang.ref.WeakReference

/** All libtorrent JNI interactions, INCLUDING .torrent metadata parsing and
 * magnet retrieval, execute in the separate :torrent service process.
 * Cross-process replies contain only a small verified identifier. Descriptor
 * and file-list data travel through app-private files, not Binder payloads.
 */
internal object TorrentMetadataBridge {
    const val RECEIVER="asterion.torrent.result_receiver"
    private val idPattern=Regex("[0-9a-f]{24}")
    private fun root(context:Context)=File(context.filesDir,"torrent-jobs").apply{mkdirs()}
    private fun id(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).take(12).joinToString(""){"%02x".format(it)}
    private fun checkId(value:String):String {
        require(idPattern.matches(value)){"Invalid torrent job identifier"}
        return value
    }
    private fun callback(a:Activity,folder:String):ResultReceiver {
        val activity=WeakReference(a)
        return object:ResultReceiver(Handler(Looper.getMainLooper())){
            override fun onReceiveResult(resultCode:Int,data:Bundle?){
                val owner=activity.get()?:return
                if(owner.isFinishing || owner.isDestroyed)return
                if(resultCode==1){
                    runCatching{
                        showPreparedFiles(owner,
                            checkId(data?.getString("job").orEmpty()),folder)
                    }.onFailure{error->
                        AlertDialog.Builder(owner).setTitle("Torrent metadata error")
                            .setMessage(error.message?:"Unable to read selected files")
                            .setPositiveButton("Close",null).show()
                    }
                }else{
                    AlertDialog.Builder(owner)
                        .setTitle("Torrent metadata unavailable")
                        .setMessage(data?.getString("error")
                            ?:"The torrent engine stopped; no files were changed")
                        .setPositiveButton("Close",null).show()
                }
            }
        }
    }
    fun prepareMagnet(a:Activity,magnet:String,folder:String) {
        if(!(magnet.startsWith("magnet:?xt=urn:btih:",true) ||
                magnet.startsWith("magnet:?xt=urn:btmh:",true))){
            Toast.makeText(a,"Invalid magnet info hash",Toast.LENGTH_LONG).show()
            return
        }
        val intent=Intent(a,TorrentTransferService::class.java).apply{
            action=TorrentTransferService.PREPARE_MAGNET
            putExtra("magnet",magnet)
            putExtra("folder",folder)
            putExtra(RECEIVER,callback(a,folder))
        }
        a.startForegroundService(intent)
        Toast.makeText(a,"Reading torrent metadata from peers…",Toast.LENGTH_LONG).show()
    }
    fun prepareDescriptor(a:Activity,bytes:ByteArray,folder:String) {
        runCatching{
            require(bytes.size in 33..8_388_608){"Invalid torrent descriptor size"}
            val hash=id(bytes)
            File(root(a),"$hash.torrent").writeBytes(bytes)
            a.startForegroundService(Intent(a,TorrentTransferService::class.java).apply{
                action=TorrentTransferService.PREPARE_DESCRIPTOR
                putExtra("job",hash)
                putExtra("folder",folder)
                putExtra(RECEIVER,callback(a,folder))
            })
            Toast.makeText(a,"Reading selected torrent files…",Toast.LENGTH_LONG).show()
        }.onFailure{
            AlertDialog.Builder(a).setTitle("Torrent metadata error")
                .setMessage(it.message).setPositiveButton("Close",null).show()
        }
    }
    /** Runs only inside TorrentTransferService's :torrent process. */
    fun prepareInTorrentProcess(context:Context,action:String,
                                intent:Intent,folder:String):String {
        require(folder in setOf("Games","Books","Videos","Assets")){
            "Unknown torrent destination"
        }
        val bytes=when(action){
            TorrentTransferService.PREPARE_MAGNET->{
                val magnet=intent.getStringExtra("magnet").orEmpty()
                require(magnet.startsWith("magnet:?xt=urn:btih:",true) ||
                    magnet.startsWith("magnet:?xt=urn:btmh:",true)){
                    "Invalid magnet"
                }
                TorrentTransfers.session().fetchMagnet(magnet,100,context.cacheDir)
                    ?:error("No reachable peers supplied torrent metadata")
            }
            TorrentTransferService.PREPARE_DESCRIPTOR->{
                val hash=checkId(intent.getStringExtra("job").orEmpty())
                File(root(context),"$hash.torrent").readBytes().also{
                    require(id(it)==hash){"Torrent descriptor was changed"}
                }
            }
            else->error("Unknown torrent metadata request")
        }
        require(bytes.size in 33..8_388_608){"Invalid torrent descriptor size"}
        val hash=id(bytes)
        val file=File(root(context),"$hash.torrent")
        if(!file.exists())file.writeBytes(bytes)
        val info=TorrentInfo(bytes)
        require(info.isValid() && info.numFiles() in 1..20000){
            "Invalid torrent file list"
        }
        val list=info.files()
        val entries=(0 until info.numFiles()).map{i->list.filePath(i)}
        if(folder=="Games")require(GameTorrentPolicy.hasGamePayload(entries)){
            "This torrent contains no identifiable game files"
        }
        val serialized=JSONObject().apply{
            put("id",hash)
            put("name",info.name())
            put("files",JSONArray().apply{
                for(i in entries.indices)put(JSONObject().apply{
                    put("path",entries[i])
                    put("size",list.fileSize(i))
                })
            })
        }
        val part=File(root(context),"$hash.manifest.part")
        try{
            part.writeText(serialized.toString())
            val target=File(root(context),"$hash.manifest.json")
            if(target.exists())check(target.delete()){
                "Cannot replace stale torrent file list"
            }
            check(part.renameTo(target)){"Cannot save torrent file list"}
        }finally{part.delete()}
        return hash
    }
    /** UI rendering is pure JSON; it never loads the native torrent library. */
    private fun showPreparedFiles(a:Activity,hash:String,folder:String) {
        val file=File(root(a),"${checkId(hash)}.manifest.json")
        val obj=JSONObject(file.readText())
        require(obj.getString("id")==hash){"Torrent manifest identity mismatch"}
        val list=obj.getJSONArray("files")
        require(list.length() in 1..20000){"Invalid torrent manifest"}
        val paths=(0 until list.length()).map{list.getJSONObject(it).getString("path")}
        val names=Array(list.length()){i->
            val e=list.getJSONObject(i)
            val risk=TorrentContentRisk.reviewReason(paths[i])
            (if(risk!=null)"[REVIEW: $risk] " else "")+
                paths[i]+"  ("+(e.getLong("size")/1024)+" KiB)"
        }
        val selected=BooleanArray(paths.size){i->
            TorrentContentRisk.selectByDefault(folder,paths[i])
        }
        AlertDialog.Builder(a).setTitle("Select files: "+obj.optString("name"))
            .setMultiChoiceItems(names,selected){_,i,checked->selected[i]=checked}
            .setPositiveButton("DOWNLOAD SELECTED"){_,_->
                if(!selected.any{it}){
                    Toast.makeText(a,"Select at least one file",Toast.LENGTH_LONG).show()
                }else if(folder=="Books" && selected.indices.any{
                    selected[it] && !paths[it].endsWith(".epub",true)
                }){
                    Toast.makeText(a,"Books supports EPUB files",Toast.LENGTH_LONG).show()
                }else{
                    val indices=selected.indices.filter{selected[it]}.joinToString(",")
                    a.startForegroundService(Intent(a,TorrentTransferService::class.java).apply{
                        action=TorrentTransferService.ADD
                        putExtra("job",hash)
                        putExtra("folder",folder)
                        putExtra("indices",indices)
                    })
                    Toast.makeText(a,"Starting selected torrent files",
                        Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Cancel",null).show()
    }
}
