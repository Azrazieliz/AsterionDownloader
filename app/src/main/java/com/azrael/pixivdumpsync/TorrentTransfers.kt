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
    private val engine = SessionManager()
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
        val keys = prefs.all.keys.filter { it.startsWith("job_") }.sortedDescending()
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
        Toast.makeText(a, "Retrieving torrent file list from peers…", Toast.LENGTH_LONG).show()
        thread(name = "asterion-magnet-metadata") {
            val result = runCatching {
                require(magnet.startsWith("magnet:?xt=urn:btih:", true) ||
                    magnet.startsWith("magnet:?xt=urn:btmh:", true)) { "Invalid magnet info hash" }
                session().fetchMagnet(magnet, 100, a.cacheDir)
                    ?: error("No peers returned torrent metadata")
            }
            a.runOnUiThread {
                result.onSuccess { prepareDescriptor(a, it, folder) }
                    .onFailure {
                        AlertDialog.Builder(a).setTitle("Magnet metadata unavailable")
                            .setMessage(it.message ?: "Could not retrieve peer metadata")
                            .setPositiveButton("Close", null).show()
                    }
            }
        }
    }
    fun prepareDescriptor(a: Activity, bytes: ByteArray, folder: String) {
        runCatching {
            require(bytes.size in 33..8_388_608) { "Invalid torrent descriptor size" }
            val info = TorrentInfo(bytes)
            require(info.isValid() && info.numFiles() in 1..20000) { "Invalid torrent file list" }
            val files = info.files()
            val options = Array(info.numFiles()) { i ->
                "${files.filePath(i)}  (${files.fileSize(i) / 1024} KiB)"
            }
            val selected = BooleanArray(info.numFiles()) { i ->
                files.fileName(i).lowercase().endsWith(".zip") ||
                    files.fileName(i).lowercase().endsWith(".apk")
            }
            if (!selected.any { it }) selected.fill(true)
            AlertDialog.Builder(a).setTitle("Select files: " + info.name())
                .setMultiChoiceItems(options, selected) { _, index, checked -> selected[index] = checked }
                .setPositiveButton("DOWNLOAD SELECTED") { _, _ ->
                    if (!selected.any { it }) {
                        Toast.makeText(a, "Select at least one file", Toast.LENGTH_LONG).show()
                    } else {
                        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
                            .take(12).joinToString("") { "%02x".format(it) }
                        val descriptor = File(File(a.filesDir, "torrent-jobs").apply { mkdirs() }, "$hash.torrent")
                        descriptor.writeBytes(bytes)
                        val indices = selected.indices.filter { selected[it] }.joinToString(",")
                        val intent = Intent(a, TorrentTransferService::class.java).apply {
                            action = TorrentTransferService.ADD
                            putExtra("job", hash)
                            putExtra("folder", folder)
                            putExtra("indices", indices)
                        }
                        a.startForegroundService(intent)
                        Toast.makeText(a, "Starting native peer transfer", Toast.LENGTH_LONG).show()
                    }
                }.setNegativeButton("Cancel", null).show()
        }.onFailure {
            AlertDialog.Builder(a).setTitle("Invalid torrent")
                .setMessage(it.message).setPositiveButton("Close", null).show()
        }
    }
}

/** Foreground torrent transfer, progress reporting, file integrity and public export. */
class TorrentTransferService : Service() {
    companion object {
        const val ADD = "asterion.torrent.ADD"
        const val PAUSE = "asterion.torrent.PAUSE"
        const val RESUME = "asterion.torrent.RESUME"
        private const val CHANNEL = "asterion_peer_transfers"
        private const val NOTIFICATION = 56823
        private val inFlight = ConcurrentHashMap.newKeySet<String>()
        private val paused = ConcurrentHashMap.newKeySet<String>()
    }
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
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification("Preparing torrent…"))
        val id = intent?.getStringExtra("job") ?: return START_NOT_STICKY
        when (intent.action) {
            PAUSE, RESUME -> {
                val pause = intent.action == PAUSE
                if (pause) paused.add(id) else paused.remove(id)
                update(id, if (pause) "Paused" else "Resuming")
                return START_STICKY
            }
            ADD -> {
                val folder = intent.getStringExtra("folder")?.takeIf { it == "Games" || it == "Books" }
                    ?: "Games"
                val indices = intent.getStringExtra("indices") ?: ""
                if (inFlight.add(id)) thread(name = "asterion-torrent-$id") {
                    try { transfer(id, folder, indices) }
                    catch (e: Throwable) { update(id, "FAILED: " + (e.message ?: e.javaClass.simpleName)) }
                    finally {
                        inFlight.remove(id)
                        if (inFlight.isEmpty()) stopSelf()
                    }
                }
                return START_REDELIVER_INTENT
            }
        }
        return START_NOT_STICKY
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
        val storage = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: error("External storage unavailable")
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
        while (true) {
            if (paused.contains(id)) {
                handle.pause()
                update(id, "Paused")
                while (paused.contains(id)) Thread.sleep(500)
                handle.resume()
            }
            val status = handle.status()
            val percent = (status.progress() * 100).toInt().coerceIn(0, 100)
            update(id, "${info.name().take(32)} $percent% · ${status.downloadRate()/1024} KiB/s · ${status.numPeers()} peers", percent)
            if (status.isFinished()) break // libtorrent has verified selected pieces
            Thread.sleep(2000)
        }
        update(id, "Verified. Exporting selected files…", 100)
        var copied = 0
        for (index in selected.sorted()) {
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
                contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
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
    }
}
