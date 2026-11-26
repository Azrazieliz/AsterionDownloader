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
    // Each release gets ONE native metadata resolver at a time. Repeated
    // FILES taps used to spawn duplicate 100-second peer waits without
    // producing a selectable manifest. The lease also expires if the
    // isolated torrent process dies before ResultReceiver can reply.
    private val preparing=java.util.concurrent.ConcurrentHashMap<String,Long>()
    internal fun claimRequest(key:String,now:Long):Boolean{
        require(key.isNotBlank())
        val until=now+180_000L
        while(true){
            val current=preparing.putIfAbsent(key,until)
            if(current==null)return true
            if(current>now)return false
            if(preparing.replace(key,current,until))return true
        }
    }
    internal fun releaseRequest(key:String){preparing.remove(key)}
    private fun requestKey(value:String):String{
        val parsed=normalizedHexInfoHash(value)
        if(parsed!=null)return "magnet:"+parsed
        return "metadata:"+id(value.toByteArray(Charsets.UTF_8))
    }
    private fun root(context:Context)=File(context.filesDir,"torrent-jobs").apply{mkdirs()}
    private fun id(bytes:ByteArray)=MessageDigest.getInstance("SHA-256")
        .digest(bytes).take(12).joinToString(""){"%02x".format(it)}
    /** Authenticate a locally cached .torrent against BOTH its SHA-256
     * storage ID and the magnet's v1 info hash before displaying a chooser.
     * A stale pointer must never silently cross-bind a different release.
     */
    internal fun cachedDescriptorMatchesJob(
        bytes:ByteArray,jobId:String,infoHash:String):Boolean = runCatching {
        bytes.size in 33..8_388_608 && idPattern.matches(jobId) &&
            id(bytes)==jobId &&
            BencodedTorrentManifest.parse(bytes).infoHashHex.equals(
                infoHash,ignoreCase=true)
    }.getOrDefault(false)

    private fun checkId(value:String):String {
        require(idPattern.matches(value)){"Invalid torrent job identifier"}
        return value
    }
    private fun callback(a:Activity,folder:String,request:String):ResultReceiver {
        val activity=WeakReference(a)
        return object:ResultReceiver(Handler(Looper.getMainLooper())){
            override fun onReceiveResult(resultCode:Int,data:Bundle?){
                releaseRequest(request)
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
    internal fun normalizedHexInfoHash(magnet:String):String? {
        val hash=CloudTorrentPolicy.infoHash(magnet)?:return null
        if(hash.length==40)return hash.lowercase(java.util.Locale.ROOT)
        if(hash.length!=32)return null
        val alphabet="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bytes=java.io.ByteArrayOutputStream(20)
        var value=0
        var bits=0
        for(letter in hash){
            val digit=alphabet.indexOf(letter)
            if(digit<0)return null
            value=(value shl 5) or digit
            bits+=5
            if(bits>=8){
                bits-=8
                bytes.write((value shr bits) and 255)
                value=value and ((1 shl bits)-1)
            }
        }
        return bytes.toByteArray().takeIf{it.size==20}
            ?.joinToString(""){"%02x".format(it)}
    }
    /**
     * Metadata caches reduce risky peer/JNI metadata sessions, but are
     * UNTRUSTED. The exact SHA-1 of the raw info dictionary must match the
     * selected magnet before a file selection is offered.
     */
    private fun cachedDescriptor(hash:String):ByteArray? {
        val urls=listOf(
            "https://torrage.info/torrent.php?h="+hash.uppercase(java.util.Locale.ROOT),
            "https://itorrents.net/torrent/"+
                hash.uppercase(java.util.Locale.ROOT)+".torrent"
        )
        for(url in urls){
            val connection=runCatching{
                java.net.URL(url).openConnection() as java.net.HttpURLConnection
            }.getOrNull()?:continue
            try{
                connection.connectTimeout=7_000
                connection.readTimeout=9_000
                connection.instanceFollowRedirects=false
                connection.setRequestProperty("Accept","application/x-bittorrent")
                connection.setRequestProperty("User-Agent","AsterionDownloader/1.3")
                if(connection.responseCode!=200)continue
                if(connection.contentLengthLong>8_388_608L)continue
                val source=connection.inputStream
                val input=if(connection.contentEncoding.equals("gzip",true))
                    java.util.zip.GZIPInputStream(source) else source
                val output=java.io.ByteArrayOutputStream()
                input.use { stream ->
                    val block=ByteArray(16_384)
                    val deadline=android.os.SystemClock.elapsedRealtime()+15_000L
                    while(true){
                        if(Thread.currentThread().isInterrupted ||
                            android.os.SystemClock.elapsedRealtime()>=deadline)
                            throw java.io.IOException("Metadata cache read timed out")
                        val count=stream.read(block)
                        if(count<0)break
                        if(output.size()+count>8_388_608)
                            throw java.io.IOException("Metadata exceeds size limit")
                        output.write(block,0,count)
                    }
                }
                val candidate=output.toByteArray()
                val manifest=BencodedTorrentManifest.parse(candidate)
                if(manifest.infoHashHex.equals(hash,true))return candidate
            }catch(_:Exception){
                // The cache is not authoritative. Continue to the next
                // HTTPS source, then use the guarded native path if necessary.
            }finally{connection.disconnect()}
        }
        return null
    }
    fun prepareMagnet(a:Activity,magnet:String,folder:String,
                      ownerReviewed:Boolean=false) {
        if(!(magnet.startsWith("magnet:?xt=urn:btih:",true) ||
                magnet.startsWith("magnet:?xt=urn:btmh:",true))){
            Toast.makeText(a,"Invalid magnet info hash",Toast.LENGTH_LONG).show()
            return
        }
        val hash=normalizedHexInfoHash(magnet)
        val request=requestKey(magnet)
        if(!claimRequest(request,android.os.SystemClock.elapsedRealtime())){
            Toast.makeText(a,"Already retrieving this release's file list",
                Toast.LENGTH_SHORT).show()
            return
        }
        // Reopen a previously authenticated, validated local descriptor
        // immediately. The pointer is advisory; the manifest and exact hash
        // were verified when stored, never from untrusted search poster data.
        if(hash!=null){
            val pointer=File(root(a),"cache-$hash.pointer")
            val existing=runCatching{pointer.readText().trim()}.getOrNull()
            if(existing!=null && idPattern.matches(existing)){
                val descriptor=File(root(a),"$existing.torrent")
                val manifest=File(root(a),"$existing.manifest.json")
                val validated=runCatching {
                    descriptor.isFile && manifest.isFile &&
                        cachedDescriptorMatchesJob(descriptor.readBytes(),existing,hash) &&
                        JSONObject(manifest.readText()).optString("id")==existing
                }.getOrDefault(false)
                if(validated){
                    val opened=runCatching{showPreparedFiles(a,existing,folder)}
                    if(opened.isSuccess){
                        releaseRequest(request)
                        return
                    }
                }
                // Previously a corrupt/stale cached manifest returned here,
                // leaving FILES taps inert. Preserve the original files but
                // discard only the advisory pointer and retry genuine metadata.
                runCatching{pointer.delete()}
            }
        }
        Toast.makeText(a,"Checking authenticated torrent metadata cache first",
            Toast.LENGTH_LONG).show()
        kotlin.concurrent.thread(name="asterion-torrent-https-metadata"){
            val bytes=hash?.let{cachedDescriptor(it)}
            a.runOnUiThread {
                if(a.isFinishing || a.isDestroyed){
                    releaseRequest(request)
                    return@runOnUiThread
                }
                if(bytes!=null){
                    releaseRequest(request)
                    prepareDescriptor(a,bytes,folder)
                    val descriptorId=id(bytes)
                    if(hash!=null &&
                        File(root(a),"$descriptorId.manifest.json").isFile)
                        runCatching{File(root(a),"cache-$hash.pointer")
                            .writeText(descriptorId)}
                    return@runOnUiThread
                }
                val safety=TorrentSafetyGate.assess(a)
                if(!safety.allowed){
                    releaseRequest(request)
                    val dialog=AlertDialog.Builder(a)
                        .setTitle("Torrent device-safety hold")
                        .setMessage(safety.reason+
                            "\nNo validated cached file list was available.")
                        .setNegativeButton("Keep paused",null)
                    if(TorrentSafetyGate.incidentHold(a))
                        dialog.setPositiveButton("Retry isolated torrent"){_,_->
                            val reviewed=runCatching{
                                TorrentSafetyGate.ownerReviewedRetry(a)
                            }.getOrElse{TorrentSafetyGate.Admission(
                                false,it.message?:"Cannot acknowledge incident")}
                            if(reviewed.allowed)
                                prepareMagnet(a,magnet,folder,ownerReviewed=true)
                            else AlertDialog.Builder(a)
                                .setTitle("Torrent remains paused")
                                .setMessage(reviewed.reason)
                                .setPositiveButton("Close",null).show()
                        }
                    dialog.show()
                    return@runOnUiThread
                }
                val intent=Intent(a,TorrentTransferService::class.java).apply{
                    action=TorrentTransferService.PREPARE_MAGNET
                    putExtra("magnet",magnet)
                    putExtra("folder",folder)
                    putExtra(TorrentTransferService.OWNER_REVIEWED_RETRY,
                        ownerReviewed)
                    putExtra(RECEIVER,callback(a,folder,request))
                }
                runCatching{a.startForegroundService(intent)}
                    .onSuccess{Toast.makeText(a,
                        "Waiting for peer metadata (up to 100 seconds)",
                        Toast.LENGTH_LONG).show()}
                    .onFailure{
                        releaseRequest(request)
                        AlertDialog.Builder(a).setTitle("Torrent engine unavailable")
                            .setMessage(it.message?:"Android could not start torrent metadata")
                            .setPositiveButton("Close",null).show()
                    }
            }
        }
    }
    fun prepareDescriptor(a:Activity,bytes:ByteArray,folder:String) {
        // Descriptor inspection is ordinary bounded bencode parsing and
        // must not start libtorrent/JNI. A previous device-wide crash during
        // a native request cannot prevent safe local file-tree inspection.
        runCatching {
            require(folder in setOf("Games","Books","Videos","Assets")){
                "Unknown torrent destination"
            }
            val hash=id(bytes)
            val manifest=File(root(a),"$hash.manifest.json")
            // A cached file chooser without its original descriptor can
            // never start an actual transfer; reconstruct both atomically.
            val descriptor=File(root(a),"$hash.torrent")
            // Never trust the presence of old files alone: a crash can leave
            // a valid descriptor beside a stale/corrupt chooser manifest.
            // In that case regenerate the index from the selected descriptor
            // rather than giving the owner a permanently broken FILES action.
            val reusable=manifest.isFile && descriptor.isFile && runCatching {
                descriptor.readBytes().contentEquals(bytes) &&
                    JSONObject(manifest.readText()).let{cached->
                        cached.optString("id")==hash &&
                            cached.optJSONArray("files")?.length()
                                ?.let{it in 1..20_000}==true
                    }
            }.getOrDefault(false)
            if(reusable && runCatching{
                showPreparedFiles(a,hash,folder)
            }.isSuccess)return
            val inspected=BencodedTorrentManifest.parse(bytes)
            val names=inspected.files.map{it.path}
            if(folder=="Games")require(GameTorrentPolicy.hasGamePayload(names)){
                "This torrent contains no identifiable game files"
            }
            val payload=JSONObject().apply{
                put("id",hash)
                put("name",inspected.name)
                put("files",JSONArray().apply{
                    for(file in inspected.files)put(JSONObject().apply{
                        put("path",file.path)
                        put("size",file.size)
                    })
                })
            }.toString()
            // AtomicFile is used for both descriptor and manifest so an
            // interrupted write never exposes a half-valid file list.
            fun writeAtomic(file:File,source:ByteArray){
                val atomic=android.util.AtomicFile(file)
                val output=atomic.startWrite()
                try{
                    output.write(source)
                    atomic.finishWrite(output)
                }catch(t:Throwable){
                    atomic.failWrite(output)
                    throw t
                }
            }
            writeAtomic(File(root(a),"$hash.torrent"),bytes)
            writeAtomic(manifest,payload.toByteArray(Charsets.UTF_8))
            showPreparedFiles(a,hash,folder)
        }.onFailure{
            AlertDialog.Builder(a).setTitle("Torrent file-list error")
                .setMessage(it.message?:"Unable to inspect selected torrent")
                .setPositiveButton("Close",null).show()
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
        // Pure JVM bounded validation precedes the libtorrent JNI parser,
        // including when metadata came from magnet peers rather than an
        // already-validated user-selected .torrent. Keep unsafe paths,
        // symbolic links and v2-only descriptors out of native file lists.
        val checked=BencodedTorrentManifest.parse(bytes)
        require(checked.files.size in 1..20_000){"Invalid torrent file count"}
        if(action==TorrentTransferService.PREPARE_MAGNET){
            val requested=normalizedHexInfoHash(intent.getStringExtra("magnet").orEmpty())
                ?:error("Unsupported magnet identity; a v1 info hash is required")
            require(checked.infoHashHex.equals(requested,true)){
                "Peer metadata does not match the requested torrent"
            }
        }
        val hash=id(bytes)
        val file=File(root(context),"$hash.torrent")
        if(!file.exists())file.writeBytes(bytes)
        val info=TorrentInfo(bytes)
        require(info.isValid() && info.numFiles()==checked.files.size){
            "Native torrent file list disagreed with the bounded descriptor"
        }
        val list=info.files()
        val entries=(0 until info.numFiles()).map{i->list.filePath(i)}
        require(checked.files.indices.all{i->
            checked.files[i].path==entries[i] && checked.files[i].size==list.fileSize(i)
        }){"Native file ordering or lengths disagree with the verified descriptor"}
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
        val descriptor=File(root(a),"$hash.torrent")
        require(descriptor.isFile && descriptor.length() in 33L..8_388_608L){
            "Original torrent descriptor missing or invalid"
        }
        val bytes=descriptor.readBytes()
        require(id(bytes)==hash){"Torrent descriptor identity mismatch"}
        val checked=BencodedTorrentManifest.parse(bytes)
        require(checked.files.size==paths.size && checked.files.indices.all{i->
            checked.files[i].path==paths[i] &&
                checked.files[i].size==list.getJSONObject(i).getLong("size")
        }){"Cached file list disagrees with the original descriptor"}
        if(folder=="Games")require(GameTorrentPolicy.hasGamePayload(paths)){
            "This torrent contains no identifiable game files"
        }
        // Rendering 20,000 CheckBox rows in one AlertDialog could freeze
        // System UI under native memory pressure. Retain ALL 20,000 index
        // choices in one small boolean array; render at most 80 rows at once.
        val selected=BooleanArray(paths.size){i->
            TorrentContentRisk.selectByDefault(folder,paths[i])
        }
        var ownerReviewed=false
        fun startSelected(){
            if(!selected.any{it}){
                Toast.makeText(a,"Select at least one file",Toast.LENGTH_LONG).show()
                return
            }
            if(folder=="Books" && selected.indices.any{
                selected[it] && !paths[it].endsWith(".epub",true)
            }){
                Toast.makeText(a,"Books supports EPUB files",Toast.LENGTH_LONG).show()
                return
            }
            val admission=TorrentSafetyGate.assess(a)
            if(!admission.allowed){
                val dialog=AlertDialog.Builder(a)
                    .setTitle("Torrent safety hold")
                    .setMessage(admission.reason)
                    .setNegativeButton("Keep paused",null)
                if(TorrentSafetyGate.incidentHold(a))
                    dialog.setPositiveButton("Retry isolated torrent"){_,_->
                        val reviewed=runCatching{
                            TorrentSafetyGate.ownerReviewedRetry(a)
                        }.getOrElse{TorrentSafetyGate.Admission(
                            false,it.message?:"Cannot acknowledge incident")}
                        if(reviewed.allowed){
                            ownerReviewed=true
                            startSelected()
                        }else AlertDialog.Builder(a)
                            .setTitle("Torrent remains paused")
                            .setMessage(reviewed.reason)
                            .setPositiveButton("Close",null).show()
                    }
                dialog.show()
                return
            }
            val indices=selected.indices.filter{selected[it]}.joinToString(",")
            // A cancelled job has an authoritative AtomicFile tombstone
            // shared across processes. Only this explicit DOWNLOAD SELECTED
            // action may re-arm it; setting the preferences boolean to
            // false alone cannot clear the marker and made reselect inert.
            if(!TorrentTransfers.clearCancellationForExplicitSelection(a,hash)){
                AlertDialog.Builder(a).setTitle("Torrent still cancelled")
                    .setMessage("Android could not clear the previous cancellation marker. "+
                        "No transfer was launched; original files are unchanged.")
                    .setPositiveButton("Close",null).show()
                return
            }
            // Persist the EXACT selected file indices and folder BEFORE
            // sending an Android foreground-service intent. The isolated
            // :torrent process can be denied/killed before onStartCommand;
            // previously no selection survived and Resume later failed.
            val saved=a.getSharedPreferences("torrent_transfers",
                Context.MODE_PRIVATE).edit()
                .putString("job_indices_$hash",indices)
                .putString("job_folder_$hash",folder)
                .putBoolean("job_paused_$hash",false)
                .putBoolean("job_cancelled_$hash",false)
                .commit()
            if(!saved){
                AlertDialog.Builder(a).setTitle("Torrent selection not saved")
                    .setMessage("Selected file indices could not be saved. "+
                        "No transfer was launched; select again.")
                    .setPositiveButton("Close",null).show()
                return
            }
            TorrentTransfers.record(a,"job_$hash",
                "QUEUED · Selected files await native worker acknowledgement")
            runCatching{
                a.startForegroundService(Intent(a,TorrentTransferService::class.java).apply{
                    action=TorrentTransferService.ADD
                    putExtra("job",hash)
                    putExtra("folder",folder)
                    putExtra("indices",indices)
                    putExtra(TorrentTransferService.OWNER_REVIEWED_RETRY,
                        ownerReviewed)
                })
            }.onSuccess{
                Toast.makeText(a,"Torrent request queued · check Transfers for actual bytes",
                    Toast.LENGTH_LONG).show()
            }.onFailure{error->
                TorrentTransfers.record(a,"job_$hash",
                    "FAILED: Torrent worker launch denied · "+
                    (error.message?:error.javaClass.simpleName).take(140))
                AlertDialog.Builder(a).setTitle("Torrent worker unavailable")
                    .setMessage(error.message?:"Could not start selected torrent transfer")
                    .setPositiveButton("Close",null).show()
            }
        }
        fun showPage(page:Int){
            if(a.isFinishing || a.isDestroyed)return
            val bounds=TorrentChooserPagination.bounds(paths.size,page)
            val names=Array(bounds.last-bounds.first){offset->
                val index=bounds.first+offset
                val e=list.getJSONObject(index)
                val risk=TorrentContentRisk.reviewReason(paths[index])
                (if(risk!=null)"[REVIEW: $risk] " else "")+
                    paths[index].take(230)+"  ("+(e.getLong("size")/1024)+" KiB)"
            }
            val checked=BooleanArray(names.size){selected[bounds.first+it]}
            val heading=obj.optString("name").take(75)+" · "+
                (page+1)+"/"+TorrentChooserPagination.pageCount(paths.size)
            val builder=AlertDialog.Builder(a)
                .setTitle(heading)
                .setMessage(selected.count{it}.toString()+" / "+paths.size+
                    " selected · files "+(bounds.first+1)+"–"+bounds.last)
                .setMultiChoiceItems(names,checked){_,index,value->
                    selected[bounds.first+index]=value
                }
                .setPositiveButton("DOWNLOAD SELECTED"){_,_->startSelected()}
                .setNegativeButton(if(page==0)"CANCEL" else "PREVIOUS"){_,_->
                    if(page>0)showPage(page-1)
                }
            if(TorrentChooserPagination.hasNext(paths.size,page)){
                builder.setNeutralButton("NEXT PAGE"){_,_->showPage(page+1)}
            }
            builder.show()
        }
        showPage(0)
    }
}
