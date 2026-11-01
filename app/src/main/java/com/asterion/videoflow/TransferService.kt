package com.asterion.videoflow

import android.app.*
import android.content.*
import android.os.*
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest

object VideoEngine {
    private val busy=AtomicBoolean(false)
    private val executor=Executors.newSingleThreadExecutor()
    const val CHANNEL="videoflow-transfers"
    fun makeChannel(context: Context) {
        val channel=NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    fun notification(context: Context, message: String): Notification {
        makeChannel(context)
        val open=PendingIntent.getActivity(context, 0, Intent(context,MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context,CHANNEL).setContentTitle("Asterion · VideoFlow")
            .setContentText(message).setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setContentIntent(open).build()
    }
    fun launch(context: Context, scan: Boolean = false): Boolean {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val intent=Intent(context,TransferService::class.java).putExtra("scan",scan)
        return try {
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent) else context.startService(intent)
            true
        } catch (_: Exception) {
            false // JobService can use its own job execution when FGS launch is restricted.
        }
    }
    fun work(context: Context, scan: Boolean, onStatus: (String)->Unit = {}) {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return
        if(!busy.compareAndSet(false,true)) return
        try {
            val db=VideoDb(context)
            db.recover()
            try { YtDlp.init(context.applicationContext) }
            catch(t:Exception) {
                db.next()?.let { db.update(it.id,"error",error="Video engine unavailable: ${t.message}") }
                onStatus("Video engine initialization failed")
                return
            }
            if(scan) {
                com.azrael.pixivdumpsync.NameCrawler.scan(context,onStatus)
                val settings=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
                val max=settings.getInt("max_new",3).coerceIn(1,20)
                for(watch in db.listWatches().filter { it.enabled }) {
                    try {
                        onStatus("Scanning ${watch.label}")
                        if(!WebDiscovery.isUrl(watch.address)){
                            db.checked(watch.id,"Unsupported saved search; remove this source")
                            continue
                        }
                        val artistArchive=watch.address.startsWith("https://watchporn.to/models/")
                        val hits=if(artistArchive)
                            WebDiscovery.findWatchPornArtist(watch.address,watch.lastCheck==0L)
                            else WebDiscovery.findOnPage(watch.address,30)
                        if(hits.isEmpty()) error("No video links found on this page; use a specific feed, profile, or search watch")
                        var added=0
                        for(hit in hits) {
                            if(db.addVideo(hit.url,hit.title,watch.id)) added++
                            if(!artistArchive && added>=max) break
                        }
                        db.checked(watch.id,null)
                    } catch(t:Exception) {
                        db.checked(watch.id,t.message?: "Discovery failed")
                    }
                }
            }
            while(com.azrael.pixivdumpsync.AppPower.enabled(context)) {
                val next=db.next() ?: break
                onStatus("Downloading ${next.title}")
                db.update(next.id,"running")
                try {
                    val uri=downloadOne(context,db,next) { percent ->
                        onStatus("${next.title}: $percent%")
                    }
                    db.update(next.id,"done",100,uri)
                    if(com.azrael.pixivdumpsync.NduSettings.configured(context)){
                        com.azrael.pixivdumpsync.NduCloudQueue(context).use{queue->
                            queue.enqueue("videos",
                                android.net.Uri.parse(uri).lastPathSegment.orEmpty()
                                    .ifBlank{"video_"+next.id+".mp4"},
                                android.net.Uri.parse(uri))
                        }
                        com.azrael.pixivdumpsync.NduUploadService.launch(context)
                    }
                    onStatus("Saved ${next.title}")
                } catch(t:Exception) {
                    db.scheduleRetry(next.id,t.message?:t.javaClass.simpleName)
                    onStatus("Failed ${next.title}")
                }
            }
            if(context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getBoolean("cloud_after",false)) {
                val id=context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getInt("cloud_task",-1)
                if(id>=0) runCatching {
                    val intent=Intent("START_TASK").apply {
                        setClassName("de.felixnuesse.extract","ca.pkay.rcloneexplorer.Services.SyncService")
                        putExtra("task",id);putExtra("notification",true)
                    }
                    context.startForegroundService(intent)
                }
            }
        } finally { busy.set(false) }
    }
    fun submit(context: Context, scan: Boolean, onStatus: (String)->Unit = {}) =
        executor.submit { work(context,scan,onStatus) }
    private fun downloadOne(context: Context, db: VideoDb, item: Video, status:(Int)->Unit): String {
        val parent=File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),"videoflow-staging/${item.id}")
        parent.mkdirs()
        val template=File(parent,"%(title).160s [%(id)s].%(ext)s").absolutePath
        val prefs=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        val viaProxy=prefs.getBoolean("tor_proxy",false)
        val hidden=Uri.parse(item.url).host.orEmpty().endsWith(".onion",true)
        require(!hidden||viaProxy){"Configure a SOCKS proxy before connecting to this source"}
        val cookie=File(context.filesDir,"videoflow/cookies.txt")
        require(WebDiscovery.isUrl(item.url)){"Only public HTTP(S) video URLs are supported"}
        val connectedCookies=AuthorizedVideoSession.temporaryCookies(context,item.url)
        fun request(link:String, referer:String?=null)=
            YtDlpRequest(link).setOutputTemplate(template)
                .addOption("--no-playlist").addOption("--continue")
                .addOption("--no-overwrites").addOption("--retries","2")
                .addOption("--fragment-retries","3")
                .addOption("--retry-sleep","http:2")
                .addOption("--socket-timeout","20")
                .addOption("--user-agent",
                    "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .addOption("-f",prefs.getString("quality","best[ext=mp4]/best")
                    ?: "best[ext=mp4]/best")
                .apply {
                    if(viaProxy && hidden)addOption("--proxy", "socks5h://127.0.0.1:${prefs.getInt("tor_port",9050)}")
                    if(!referer.isNullOrBlank())addOption("--referer",referer)
                    val selected=connectedCookies?.takeIf{it.isFile&&it.length()>0}
                        ?:cookie.takeIf{it.isFile&&it.length()>0}
                    if(selected!=null)addOption("--cookies",selected.absolutePath)
                }
        fun execute(link:String,referer:String?=null) {
            val response=YtDlp.execute(request(link,referer)){progress,_,_->
                val percent=progress.toInt().coerceIn(0,100)
                db.progress(item.id,percent)
                if(percent%5==0) status(percent)
            }
            if(!response.isSuccess)error("Video extractor failed with exit code ${response.exitCode}")
        }
        // Historical builds altered original hostnames. Try the precise URL first.
        val variants=listOf(item.url)+SourceDiagnostics.alternateHosts(item.url)
        var last:Throwable?=null
        var downloaded=false
        try {
            for(candidate in variants.distinct()) {
                try {
                    execute(candidate)
                    downloaded=true
                    break
                } catch(t:Exception) {last=t}
                val media=if(viaProxy)null else runCatching{WebDiscovery.directMedia(candidate)}.getOrNull()
                if(!media.isNullOrBlank()&&media!=candidate) {
                    try {
                        execute(media,candidate)
                        downloaded=true
                        break
                    } catch(t:Exception) {last=t}
                }
            }
        } finally {
            // Do not keep browser authentication in the file system after transfer.
            connectedCookies?.delete()
        }
        if(!downloaded)throw (last?:IllegalStateException("No video extractor succeeded"))
        val file=parent.listFiles()?.filter { it.isFile && it.length()>0 &&
            it.extension.lowercase() in setOf("mp4","mkv","webm","mov","ts","m4a","mp3","flac","opus") }
            ?.maxByOrNull { it.length() } ?: error("No completed playable video found")
        val audioOnly=prefs.getString("quality","")=="bestaudio/best"
        val name=file.name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"),"_").take(210)
        val values=ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME,name)
            put(MediaStore.Downloads.MIME_TYPE,when(file.extension.lowercase()){
                "mp4"->"video/mp4";"webm"->if(audioOnly)"audio/webm" else "video/webm"
                "mkv"->"video/x-matroska";"m4a"->"audio/mp4";"mp3"->"audio/mpeg"
                "flac"->"audio/flac";"opus"->"audio/ogg";else->"video/*"
            })
            put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/VideoFlow/")
            put(MediaStore.Downloads.IS_PENDING,1)
        }
        val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
            ?: error("Cannot create Downloads/VideoFlow file")
        try {
            context.contentResolver.openOutputStream(uri,"w")!!.use { output ->
                file.inputStream().buffered().use { input -> input.copyTo(output,1024*256) }
            }
            val size=context.contentResolver.openAssetFileDescriptor(uri,"r")?.use { it.length } ?: -1
            if(size>=0 && size!=file.length()) error("Copy verification failed")
            context.contentResolver.update(uri,ContentValues().apply {put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            parent.listFiles()?.forEach { it.delete() }
            parent.delete()
            return uri.toString()
        } catch(t:Exception) {
            context.contentResolver.delete(uri,null,null)
            throw t
        }
    }
}

class TransferService: Service() {
    private val binder=Binder()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VideoEngine.makeChannel(this)
        startForeground(24014,VideoEngine.notification(this,"Preparing downloads"))
        VideoEngine.submit(applicationContext,intent?.getBooleanExtra("scan",false)==true) { text ->
            getSystemService(NotificationManager::class.java)
                .notify(24014,VideoEngine.notification(this,text.take(80)))
        }.let { future ->
            Thread {
                runCatching { future.get() }
                stopSelfResult(startId)
            }.start()
        }
        return START_REDELIVER_INTENT
    }
    override fun onDestroy() { super.onDestroy() }
}
