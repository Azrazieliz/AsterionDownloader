package com.azrael.pixivdumpsync

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * User-initiated export of SELECTED, piece-verified torrents from the private
 * quarantine. The transfer service must first produce its complete security
 * review and record QUARANTINED. This code never starts libtorrent/JNI, opens
 * an executable, or claims that piece hashes constitute a malware scan.
 *
 * Files are copied through MediaStore IS_PENDING and are published only after
 * the expected original size is copied. Successful receipts survive process
 * interruption so an export retry does not duplicate completed originals.
 */
internal object TorrentVerifiedExport {
    private val jobPattern=Regex("[a-f0-9]{24}")
    private fun root(c:Context)=File(c.filesDir,"torrent-jobs")

    /** Only the owner's on-screen confirmation may start publishing files. */
    fun request(a:android.app.Activity,id:String) {
        if(!jobPattern.matches(id))return
        val status=runCatching {
            File(root(a),"$id.status").readText()
        }.getOrDefault("")
        if(!status.contains("QUARANTINED")) {
            android.widget.Toast.makeText(a,
                "The selected torrent has not completed integrity checks",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(a)
            .setTitle("Export verified torrent files?")
            .setMessage("The selected files passed BitTorrent piece hashes and "+
                "a preliminary filename/archive review. They have NOT been "+
                "scanned for malware. An executable or game installer may "+
                "still be dangerous. Copy selected files to your Downloads "+
                "folder only if you trust their source. No file will be opened.")
            .setNegativeButton("Keep quarantined",null)
            .setPositiveButton("Export selected files"){_,_->
                runCatching {
                    a.startForegroundService(android.content.Intent(a,
                        TorrentTransferService::class.java)
                        .setAction(TorrentTransferService.EXPORT)
                        .putExtra("job",id))
                }.onFailure {
                    android.widget.Toast.makeText(a,
                        "Unable to export selected torrent: "+it.message.orEmpty(),
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    fun open(a:android.app.Activity,id:String) {
        val exported=receipts(a,id).filter{stillPublished(a,it)}
        if(exported.isEmpty()){
            android.widget.Toast.makeText(a,"No exported originals found",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val names=exported.map { it.path.substringAfterLast('/')+
            " · "+it.size+" bytes" }.toTypedArray()
        android.app.AlertDialog.Builder(a)
            .setTitle("Verified torrent files")
            .setItems(names){_,index->
                val item=exported[index]
                val ext=item.path.substringAfterLast('.',"")
                    .lowercase(Locale.ROOT)
                val mime=MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext)?:"application/octet-stream"
                runCatching {
                    a.startActivity(android.content.Intent(
                        android.content.Intent.ACTION_VIEW).apply {
                        setDataAndType(Uri.parse(item.uri),mime)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }.onFailure {
                    android.widget.Toast.makeText(a,
                        "No compatible file viewer installed",
                        android.widget.Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Close",null).show()
    }
    data class Receipt(val path:String,val uri:String,val size:Long,val sha256:String)

    fun receipts(c:Context,id:String):List<Receipt> {
        if(!jobPattern.matches(id))return emptyList()
        return runCatching {
            val items=JSONObject(File(root(c),"$id.export.json").readText())
                .getJSONArray("files")
            (0 until items.length()).map { index->
                val item=items.getJSONObject(index)
                Receipt(item.getString("path"),item.getString("uri"),
                    item.getLong("size"),item.getString("sha256"))
            }
        }.getOrDefault(emptyList())
    }

    private fun stillPublished(c:Context,receipt:Receipt):Boolean=
        runCatching {
            c.contentResolver.openFileDescriptor(Uri.parse(receipt.uri),"r")
                ?.use{it.statSize==receipt.size}?:false
        }.getOrDefault(false)

    private fun save(c:Context,id:String,receipts:List<Receipt>) {
        val json=JSONObject().apply {
            put("job",id)
            put("files",JSONArray().apply {
                receipts.forEach { row->
                    put(JSONObject().apply {
                        put("path",row.path);put("uri",row.uri)
                        put("size",row.size);put("sha256",row.sha256)
                    })
                }
            })
        }
        val atomic=android.util.AtomicFile(File(root(c),"$id.export.json"))
        val stream=atomic.startWrite()
        try{
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        }catch(t:Throwable){atomic.failWrite(stream);throw t}
    }

    /** Can only be invoked by the foreground service after the owner accepts
     * the real "not malware scanned" confirmation in the Activity.
     */
    fun publish(c:Context,id:String,onProgress:(Int,Int)->Unit):Int {
        require(jobPattern.matches(id)){"Invalid torrent job"}
        val root=root(c)
        val status=File(root,"$id.status")
        require(status.isFile && status.readText().contains("QUARANTINED")){
            "Torrent is not a completed, quarantined original"
        }
        val review=File(root,"$id.security-review.txt")
        require(review.isFile &&
            review.readText().startsWith("UNTRUSTED - NOT CLEARED FOR RELEASE")){
            "The selected originals have not completed the private review"
        }
        val manifest=JSONObject(File(root,"$id.manifest.json").readText())
            .getJSONArray("files")
        val prefs=c.getSharedPreferences("torrent_transfers",Context.MODE_PRIVATE)
        val selected=prefs.getString("job_indices_$id","").orEmpty().split(',')
            .filter{it.isNotBlank()}.map{it.toInt()}.distinct().sorted()
        require(selected.isNotEmpty() && selected.all{it in 0 until manifest.length()}){
            "No valid, owner-selected torrent files"
        }
        val destination=File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?:c.filesDir,"torrent-data/$id").canonicalFile
        val existing=receipts(c,id).associateBy{it.path}.toMutableMap()
        var done=0
        for(index in selected){
            if(Thread.currentThread().isInterrupted)
                throw IOException("Export interrupted; previously published files retained")
            val item=manifest.getJSONObject(index)
            val path=item.getString("path")
            val size=item.getLong("size")
            require(size>=0L && TorrentExportPathPolicy.valid(path)){
                "Unsafe torrent file path"
            }
            val source=File(destination,path).canonicalFile
            require(source.path.startsWith(destination.path+File.separator) &&
                source.isFile && source.length()==size){
                "Missing or incomplete selected torrent original: $path"
            }
            // Static review is NOT an antivirus scan; suspicious path or
            // archive structures are blocked before public publishing.
            val reviewResult=TorrentQuarantineGate.preliminaryReview(source,path)
            require(!reviewResult.startsWith("SUSPICIOUS")){
                "Unsafe quarantine contents: $path"
            }
            val old=existing[path]
            if(old!=null && old.size==size && stillPublished(c,old)){
                done++;onProgress(done,selected.size);continue
            }
            val targetName=path.substringAfterLast('/')
            val parent=path.substringBeforeLast('/',"")
            val relative=Environment.DIRECTORY_DOWNLOADS+
                "/Asterion/Torrents/$id/"+
                if(parent.isEmpty())"" else "$parent/"
            val ext=targetName.substringAfterLast('.',"").lowercase(Locale.ROOT)
            val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?:"application/octet-stream"
            val values=ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME,targetName)
                put(MediaStore.Downloads.MIME_TYPE,mime)
                put(MediaStore.Downloads.RELATIVE_PATH,relative)
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val resolver=c.contentResolver
            val uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                ?:throw IOException("MediaStore rejected selected file: $targetName")
            try{
                val hash=MessageDigest.getInstance("SHA-256")
                val transferred=DigestInputStream(source.inputStream()
                    .buffered(131_072),hash).use{input->
                    resolver.openOutputStream(uri,"w")?.use{output->
                        val buffer=ByteArray(131_072)
                        var count=0L
                        while(true){
                            if(Thread.currentThread().isInterrupted)
                                throw IOException("Torrent export interrupted")
                            val read=input.read(buffer)
                            if(read<0)break
                            output.write(buffer,0,read)
                            count+=read
                            if(count>size)throw IOException("Original grew during export")
                        }
                        output.flush()
                        count
                    }?:throw IOException("Cannot write selected file")
                }
                require(transferred==size && source.length()==size){
                    "Selected torrent original changed during export"
                }
                val update=ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)}
                check(resolver.update(uri,update,null,null)==1){
                    "Could not finalize downloaded file"
                }
                val digest=hash.digest().joinToString(""){"%02x".format(it)}
                existing[path]=Receipt(path,uri.toString(),size,digest)
                save(c,id,existing.values.toList())
                done++
                onProgress(done,selected.size)
                DownloadStateSignals.changed(c)
            }catch(t:Throwable){
                runCatching{resolver.delete(uri,null,null)}
                throw t
            }
        }
        return done
    }
}

internal object TorrentExportPathPolicy {
    /** Reject paths unsuitable for MediaStore or Android path segments.
     * Metadata was originally validated by BencodedTorrentManifest too.
     */
    fun valid(path:String):Boolean {
        if(path.isBlank() || path.length>4096 ||
            path.startsWith("/") || '\\' in path)return false
        val segments=path.split('/')
        return segments.size in 1..48 && segments.all { part->
            part.isNotBlank() && part!="." && part!=".." &&
            part.length<=255 && ':' !in part &&
            part.none { it.code<32 || it.code==127 || it=='\u202e' ||
                it=='\u202d' || it=='\u2066' || it=='\u2067' }
        }
    }
}
