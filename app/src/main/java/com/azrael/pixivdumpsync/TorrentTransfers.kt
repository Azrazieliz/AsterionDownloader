package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Native peer transfers using libtorrent4j. Only verified, selected files are published
 * to public Downloads; in-progress pieces remain in app-private external storage.
 * Supports both magnet metadata retrieval and .torrent descriptors. */
object TorrentTransfers {
    // Never instantiate the native engine in the main Android process.
    // Metadata and download calls are routed to :torrent via a service.
    private val engine by lazy(LazyThreadSafetyMode.SYNCHRONIZED){SessionManager()}
    @Volatile private var running = false
    @Synchronized internal fun session(): SessionManager {
        if (!running) { engine.start(); running = true }
        return engine
    }
    internal fun record(c: Context, id: String, value: String) {
        c.getSharedPreferences("torrent_transfers", Context.MODE_PRIVATE)
            .edit().putString(id, value).apply()
    }
    fun showQueue(a: Activity) {
        val prefs = a.getSharedPreferences("torrent_transfers", Context.MODE_PRIVATE)
        // job_folder_*, job_indices_* and job_paused_* are metadata,
        // NOT transfers. Previously each was presented as another torrent,
        // so Pause/Resume could address a fabricated or missing job.
        val keys = prefs.all.keys.filter {
            it.startsWith("job_") &&
                !it.startsWith("job_folder_") &&
                !it.startsWith("job_indices_") &&
                !it.startsWith("job_paused_") &&
                it.removePrefix("job_").matches(Regex("[a-f0-9]{24}"))
        }.sortedDescending()
        if (keys.isEmpty()) {
            Toast.makeText(a, "No torrent transfers yet", Toast.LENGTH_LONG).show(); return
        }
        val labels = keys.map { prefs.getString(it, "") ?: "" }.toTypedArray()
        AlertDialog.Builder(a).setTitle("BitTorrent transfers")
            .setItems(labels) { _, which ->
                val id = keys[which].removePrefix("job_")
                val controls = arrayOf("Pause transfer", "Resume transfer", "Show current status")
                AlertDialog.Builder(a).setTitle(labels[which]).setItems(controls) { _, option ->
                    if (option == 2) {
                        showQueue(a)
                    } else {
                        val intent = Intent(a, TorrentTransferService::class.java).apply {
                            action = if (option == 0) TorrentTransferService.PAUSE else TorrentTransferService.RESUME
                            putExtra("job", id)
                        }
                        a.startForegroundService(intent)
                    }
                }.setNegativeButton("Close", null).show()
            }.setPositiveButton("Refresh") { _, _ -> showQueue(a) }
            .setNegativeButton("Close", null).show()
    }
    fun prepareMagnet(a: Activity, magnet: String, folder: String) {
        TorrentMetadataBridge.prepareMagnet(a,magnet,folder)
    }
    fun prepareDescriptor(a: Activity, bytes: ByteArray, folder: String) {
        TorrentMetadataBridge.prepareDescriptor(a,bytes,folder)
    }
}

/** Foreground torrent transfer, progress reporting, file integrity and public export. */
class TorrentTransferService : Service() {
    companion object {
        const val ADD = "asterion.torrent.ADD"
        const val PAUSE = "asterion.torrent.PAUSE"
        const val RESUME = "asterion.torrent.RESUME"
        const val PREPARE_MAGNET = "asterion.torrent.PREPARE_MAGNET"
        const val PREPARE_DESCRIPTOR = "asterion.torrent.PREPARE_DESCRIPTOR"
        private const val CHANNEL = "asterion_peer_transfers"
        private const val NOTIFICATION = 56823
        private val inFlight = ConcurrentHashMap.newKeySet<String>()
        private val paused = ConcurrentHashMap.newKeySet<String>()
        // A foreground-service expiry must interrupt the running native
        // worker; otherwise the process can continue after Android revokes
        // its dataSync foreground allowance.
        private val stopRequests = ConcurrentHashMap.newKeySet<String>()
        private val workers = ConcurrentHashMap<String,Thread>()
    }
    private val metadataWorkers=ConcurrentHashMap.newKeySet<Thread>()
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(message: String, percent: Int = 0): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Torrent transfers", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, AsterionHomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setContentTitle("Asterion BitTorrent")
            .setContentText(message.take(100)).setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open).setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false).build()
    }
    private fun startJob(id:String,folder:String,indices:String):Boolean {
        if(indices.isBlank()) {
            update(id,"FAILED: Previously selected files are unavailable")
            return false
        }
        if(!inFlight.add(id))return false
        stopRequests.remove(id)
        val worker=thread(name="asterion-torrent-$id"){
            try {
                transfer(id,folder,indices)
                getSharedPreferences("torrent_transfers",MODE_PRIVATE).edit()
                    .remove("job_folder_$id").remove("job_indices_$id")
                    .remove("job_paused_$id").apply()
            }catch(e:Throwable){
                if(e is InterruptedException || stopRequests.contains(id))
                    update(id,"Paused · original pieces preserved; resume to continue")
                else
                    update(id,"FAILED: "+(e.message?:e.javaClass.simpleName))
            }finally {
                workers.remove(id)
                inFlight.remove(id)
                if(inFlight.isEmpty()){
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        workers[id]=worker
        return true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForeground(NOTIFICATION, notification("Preparing torrent…")) }
        catch(t:Exception){
            stopSelf()
            return START_NOT_STICKY
        }
        if(intent?.action==PREPARE_MAGNET || intent?.action==PREPARE_DESCRIPTOR){
            val action=intent.action.orEmpty()
            val chosen=intent.getStringExtra("folder").orEmpty()
            @Suppress("DEPRECATION")
            val receiver=if(Build.VERSION.SDK_INT>=33)
                intent.getParcelableExtra(TorrentMetadataBridge.RECEIVER,
                    android.os.ResultReceiver::class.java)
                else intent.getParcelableExtra<android.os.ResultReceiver>(
                    TorrentMetadataBridge.RECEIVER)
            val worker=thread(name="asterion-torrent-metadata"){
                try{
                    val hash=TorrentMetadataBridge.prepareInTorrentProcess(
                        applicationContext,action,intent,chosen)
                    receiver?.send(1,android.os.Bundle().apply{putString("job",hash)})
                }catch(t:Throwable){
                    receiver?.send(0,android.os.Bundle().apply{
                        putString("error",t.message?:t.javaClass.simpleName)})
                }finally{
                    metadataWorkers.remove(Thread.currentThread())
                    if(metadataWorkers.isEmpty() && inFlight.isEmpty()){
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            metadataWorkers.add(worker)
            return START_NOT_STICKY
        }
        val id=intent?.getStringExtra("job")
        if(id.isNullOrBlank()){
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        val prefs=getSharedPreferences("torrent_transfers",MODE_PRIVATE)
        when(intent.action){
            PAUSE->{
                paused.add(id)
                prefs.edit().putBoolean("job_paused_$id",true).apply()
                update(id,"Paused")
                if(inFlight.isEmpty())stopSelfResult(startId)
                return START_NOT_STICKY
            }
            RESUME->{
                paused.remove(id)
                prefs.edit().putBoolean("job_paused_$id",false).apply()
                val folder=prefs.getString("job_folder_$id","Videos")?:"Videos"
                val indices=prefs.getString("job_indices_$id","").orEmpty()
                if(!inFlight.contains(id))startJob(id,folder,indices)
                else update(id,"Resuming")
                return START_NOT_STICKY
            }
            ADD->{
                val folder=intent.getStringExtra("folder")
                    ?.takeIf{it in setOf("Games","Books","Videos","Assets")}
                    ?:prefs.getString("job_folder_$id","Videos")?:"Videos"
                val indices=intent.getStringExtra("indices")
                    ?:prefs.getString("job_indices_$id","").orEmpty()
                prefs.edit().putString("job_folder_$id",folder)
                    .putString("job_indices_$id",indices).apply()
                if(prefs.getBoolean("job_paused_$id",false))paused.add(id)
                else paused.remove(id)
                startJob(id,folder,indices)
                return START_NOT_STICKY
            }
        }
        stopSelfResult(startId)
        return START_NOT_STICKY
    }
    /** Android 15+ expires dataSync foreground time. Unlike a redelivered
     * service intent, this leaves a durable resumable job without crashing
     * the main app or immediately restarting the same native operation.
     */
    override fun onTimeout(startId:Int,fgsType:Int) {
        val prefs=getSharedPreferences("torrent_transfers",MODE_PRIVATE)
        metadataWorkers.toList().forEach{it.interrupt()}
        for(id in inFlight){
            stopRequests.add(id)
            paused.add(id)
            prefs.edit().putBoolean("job_paused_$id",true).apply()
            workers[id]?.interrupt()
            update(id,"Paused by Android · resume torrent to continue")
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        metadataWorkers.toList().forEach{it.interrupt()}
        for(id in inFlight){
            stopRequests.add(id)
            workers[id]?.interrupt()
        }
        super.onDestroy()
    }

    private fun update(id: String, state: String, progress: Int = 0) {
        TorrentTransfers.record(this, "job_$id", "${id.take(8)} · $state")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION, notification(state, progress))
    }
    private fun transfer(id: String, folder: String, encoded: String) {
        val descriptor = File(File(filesDir, "torrent-jobs"), "$id.torrent")
        require(descriptor.isFile) { "Torrent metadata missing" }
        val info = TorrentInfo(descriptor)
        require(info.isValid()) { "Torrent is invalid" }
        val selected = encoded.split(',').mapNotNull { it.toIntOrNull() }.toSet()
        require(selected.isNotEmpty() && selected.all { it in 0 until info.numFiles() }) {
            "Invalid selected file indices"
        }
        val priorities = Array(info.numFiles()) {
            if (it in selected) Priority.DEFAULT else Priority.IGNORE
        }
        // Keep verified pieces in app-private durable storage even if
        // external app storage is not mounted. The MediaStore export below
        // still publishes selected finished files to public Downloads.
        val storage = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: filesDir
        val destination = File(storage, "torrent-data/$id").apply { mkdirs() }
        val files = info.files()
        selected.forEach { index ->
            val candidate = File(destination, files.filePath(index)).canonicalFile
            require(candidate.path.startsWith(destination.canonicalPath + File.separator)) {
                "Unsafe torrent path"
            }
        }
        val session = TorrentTransfers.session()
        session.download(info, destination, null, priorities, null, torrent_flags_t())
        var handle = session.find(info.infoHash())
        var attempt = 0
        while (handle == null && attempt++ < 100) {
            Thread.sleep(300)
            handle = session.find(info.infoHash())
        }
        require(handle != null && handle.isValid()) { "Torrent engine did not accept descriptor" }
        var lastProgress=0f
        var lastAdvance=android.os.SystemClock.elapsedRealtime()
        while (true) {
            if(stopRequests.contains(id) || Thread.currentThread().isInterrupted)
                throw InterruptedException("Torrent transfer stopped; pieces retained")
            if (paused.contains(id)) {
                handle.pause()
                update(id, "Paused")
                while (paused.contains(id)) {
                    if(stopRequests.contains(id) || Thread.currentThread().isInterrupted)
                        throw InterruptedException("Torrent paused by service timeout")
                    Thread.sleep(500)
                }
                handle.resume()
                lastAdvance=android.os.SystemClock.elapsedRealtime()
            }
            val status = handle.status()
            val currentProgress=status.progress()
            val now=android.os.SystemClock.elapsedRealtime()
            if(currentProgress>lastProgress){
                lastProgress=currentProgress
                lastAdvance=now
            }
            // Do not leave a zero-peer torrent silently queued forever.
            // Keep the descriptor and partial pieces for a later retry.
            if(status.numPeers()==0 && now-lastAdvance>=10L*60_000L)
                throw java.io.IOException(
                    "No peers transferred pieces for 10 minutes; torrent stalled, pieces retained")
            if(now-lastAdvance>=20L*60_000L)
                throw java.io.IOException(
                    "Torrent made no verified piece progress for 20 minutes; pieces retained")
            val percent = (currentProgress * 100).toInt().coerceIn(0, 100)
            update(id, "${info.name().take(32)} $percent% · ${status.downloadRate()/1024} KiB/s · ${status.numPeers()} peers", percent)
            if (status.isFinished()) break // libtorrent has verified selected pieces
            Thread.sleep(2000)
        }
        update(id, "Verified. Exporting selected files…", 100)
        var copied = 0
        for (index in selected.sorted()) {
            if(stopRequests.contains(id) || Thread.currentThread().isInterrupted)
                throw InterruptedException("Torrent export interrupted; pieces retained")
            val filename = files.filePath(index)
            val source = File(destination, filename).canonicalFile
            require(source.path.startsWith(destination.canonicalPath + File.separator) && source.isFile) {
                "Completed file is missing: $filename"
            }
            require(source.length() == files.fileSize(index)) { "File size mismatch: $filename" }
            val relative = filename.replace('\\','/').split('/')
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
            val targetDir = "Download/AsterionDownloader/$folder/Torrents/$id/" +
                relative.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, source.name)
                put(MediaStore.Downloads.MIME_TYPE, when (source.extension.lowercase()) {
                    "zip" -> "application/zip"
                    "apk" -> "application/vnd.android.package-archive"
                    "pdf" -> "application/pdf"
                    "epub" -> "application/epub+zip"
                    "mp4" -> "video/mp4"
                    "webm" -> "video/webm"
                    "mkv" -> "video/x-matroska"
                    else -> "application/octet-stream"
                })
                put(MediaStore.Downloads.RELATIVE_PATH, targetDir)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Cannot publish ${source.name}")
            try {
                contentResolver.openOutputStream(uri, "w")!!.use { output ->
                    source.inputStream().use { input -> input.copyTo(output, 131072) }
                }
                val published=contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                check(published==1){
                    "Downloaded torrent file was written but not published to Downloads"
                }
            } catch (e: Throwable) {
                contentResolver.delete(uri, null, null)
                throw e
            }
            copied++
            update(id, "Exported $copied/${selected.size}: ${source.name}", 100)
        }
        update(id, "COMPLETED · $copied verified files in Downloads", 100)
        // Public files are now independently accessible; avoid retaining a duplicate multi-GB cache.
        destination.deleteRecursively()
        descriptor.delete()
        File(File(filesDir,"torrent-jobs"),"$id.manifest.json").delete()
    }
}
