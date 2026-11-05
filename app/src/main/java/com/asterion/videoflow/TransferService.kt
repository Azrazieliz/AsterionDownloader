package com.asterion.videoflow

import android.app.*
import android.content.*
import android.os.*
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import android.media.MediaMetadataRetriever
import java.io.File
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.ffmpegkit_maintained.ytdlp.YtDlp
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest

class FullVersionUnavailable(message:String):Exception(message)

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
            .setOngoing(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null,"STOP ALL BACKFILL",
                com.azrael.pixivdumpsync.EmergencyBackfillStopReceiver.action(context)).build())
            .build()
    }
    fun launch(context: Context, scan: Boolean = false,
               targetHost:String?=null,allowJobFallback:Boolean=true): Boolean {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val intent=Intent(context,TransferService::class.java).putExtra("scan",scan)
            .putExtra("target_site_host",targetHost)
        return try {
            if(Build.VERSION.SDK_INT>=26) context.startForegroundService(intent)
            else context.startService(intent)
            true
        } catch (_: Exception) {
            // Android 14+ often forbids starting a foreground service from a
            // source-scan callback or background receiver. Persist an imminent
            // JobScheduler request instead of silently abandoning the queue.
            if(allowJobFallback)Scheduling.requestImmediate(context,scan,targetHost)
            false
        }
    }
    fun work(context: Context, scan: Boolean, onStatus: (String)->Unit = {},
             targetHost:String?=null) {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return
        if(!busy.compareAndSet(false,true)) return
        com.azrael.pixivdumpsync.BackfillPerformance.backgroundWorker()
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
                if(targetHost==null)
                    com.azrael.pixivdumpsync.NameCrawler.scan(context,onStatus)
                // Independent per-provider discovery agents run concurrently.
                // Downloads still use their durable shared queue to avoid OOM.
                val scans=db.listWatches().filter {
                    it.enabled && com.azrael.pixivdumpsync.SiteControls.any(context,it.address) &&
                    (targetHost==null ||
                        com.azrael.pixivdumpsync.SiteControls.key(it.address)==targetHost)
                }.map{selected->
                    com.azrael.pixivdumpsync.BackfillAgents.submit(
                        context,"videoflow:"+selected.address){
                        for(watch in listOf(selected)){
                    if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
                        Thread.currentThread().isInterrupted)break
                    try {
                        onStatus("Scanning ${watch.label}")
                        if(!WebDiscovery.isUrl(watch.address)){
                            db.checked(watch.id,"Unsupported saved search; remove this source")
                            continue
                        }
                        val artistArchive=watch.address.startsWith("https://watchporn.to/models/")
                        val hits=if(artistArchive){
                            val latest=WebDiscovery.watchPornArtistPage(
                                watch.address,watch.address)
                            val collected=linkedMapOf<String,VideoHit>()
                            for(hit in latest.hits)collected.putIfAbsent(hit.url,hit)
                            val backfill=!watch.backfillComplete &&
                                com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address)
                            var cursor=if(backfill)watch.cursor?:latest.next else null
                            val visited=hashSetOf(watch.address)
                            var pages=0
                            while(cursor!=null && pages<8 && visited.add(cursor) &&
                                com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                                !Thread.currentThread().isInterrupted){
                                val history=WebDiscovery.watchPornArtistPage(
                                    watch.address,cursor)
                                for(hit in history.hits)collected.putIfAbsent(hit.url,hit)
                                cursor=history.next
                                pages++
                            }
                            if(backfill && com.azrael.pixivdumpsync.AppPower.enabled(context))
                                db.setBackfill(watch.id,cursor,cursor==null)
                            collected.values.toList()
                        }else{
                            val collected=linkedMapOf<String,VideoHit>()
                            val latest=WebDiscovery.page(watch.address,watch.address)
                            for(hit in latest.hits)collected.putIfAbsent(hit.url,hit)
                            // Every scan checks latest posts. A durable cursor then resumes
                            // older pages, instead of always resetting at the homepage.
                            val backfill=!watch.backfillComplete &&
                                com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address)
                            var cursor=if(backfill)watch.cursor?:latest.next else null
                            val visited=HashSet<String>()
                            var pages=0
                            while(cursor!=null && pages<(if(backfill)8 else 1) &&
                                collected.size<650 && visited.add(cursor) &&
                                com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                                !Thread.currentThread().isInterrupted){
                                val page=WebDiscovery.page(cursor,watch.address)
                                for(hit in page.hits)collected.putIfAbsent(hit.url,hit)
                                cursor=page.next
                                pages++
                            }
                            if(backfill && com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                                com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address) &&
                                !Thread.currentThread().isInterrupted)
                                db.setBackfill(watch.id,cursor,cursor==null)
                            collected.values.toList()
                        }
                        if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
                            Thread.currentThread().isInterrupted)break
                        if(!com.azrael.pixivdumpsync.SiteControls.any(context,watch.address))continue
                        if(hits.isEmpty())error("No video entries indexed from this provider; a site-specific parser or login may be required")
                        var added=0
                        for(hit in hits) {
                            if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
                                Thread.currentThread().isInterrupted)break
                            if(db.addVideo(hit.url,hit.title,watch.id)) added++
                            // Every observed new item is recorded; never truncate
                            // a followed provider after three videos.
                        }
                        if(hits.any{!it.preview.isNullOrBlank()}){
                            com.azrael.pixivdumpsync.SearchResultsDb(context).use{results->
                                results.save("WATCH:"+watch.label,hits.map{hit->
                                    com.azrael.pixivdumpsync.RankedHit(
                                        hit.title,hit.url,watch.label,
                                        thumbnail=hit.preview)
                                })
                            }
                        }
                        db.checked(watch.id,null)
                    } catch(t:Exception) {
                        db.checked(watch.id,t.message?: "Discovery failed")
                    }
                        }
                    }
                }
                for(scan in scans){
                    if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
                        Thread.currentThread().isInterrupted)break
                    runCatching{scan.get()}
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
                    if(t is FullVersionUnavailable) {
                        db.update(next.id,"full_only_hold",error=t.message)
                        onStatus("Full version unverified: ${next.title}")
                    }else{
                        db.scheduleRetry(next.id,t.message?:t.javaClass.simpleName)
                        onStatus("Failed ${next.title}")
                    }
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
    /** Manual per-provider discovery, independent of the busy transfer engine.
     * Save actual preview metadata and a durable cursor, NOT thousands of
     * unrequested download jobs. Download only when the user selects an item.
     */
    fun scanSite(context:Context,address:String,area:String,stop:()->Boolean){
        val host=com.azrael.pixivdumpsync.SiteControls.key(address)
        val db=VideoDb(context)
        try{
            var watch=db.listWatches().firstOrNull{
                com.azrael.pixivdumpsync.SiteControls.key(it.address)==host &&
                    it.address.trimEnd('/')==address.trimEnd('/')
            } ?: db.listWatches().firstOrNull{
                com.azrael.pixivdumpsync.SiteControls.key(it.address)==host
            }
            if(watch==null){
                require(db.addWatch(address,host)){"Source could not be enrolled"}
                watch=db.listWatches().firstOrNull{
                    com.azrael.pixivdumpsync.SiteControls.key(it.address)==host
                }
            }
            require(watch!=null){"Source is not recognized"}
            val selected=watch
            var cursor=selected.cursor
            val pages=ArrayList<String>()
            pages+=selected.address
            if(!cursor.isNullOrBlank() && !selected.backfillComplete)
                pages+=cursor
            val seen=HashSet<String>()
            var count=0
            var pageIndex=0
            while(pageIndex<pages.size && pageIndex<8 && !stop() &&
                com.azrael.pixivdumpsync.AppPower.enabled(context)){
                val page=pages[pageIndex++]
                if(!seen.add(page))continue
                val response=try { WebDiscovery.page(page,selected.address) }
                    catch(e:Exception){
                        db.checked(selected.id,e.message?:"Provider discovery failed")
                        com.azrael.pixivdumpsync.SourceIndexDb(context).use{
                            it.failure(address,e.message?:"Provider discovery failed")
                        }
                        throw e
                    }
                if(response.hits.isEmpty() && count==0)
                    error("No videos found; this provider needs a supported parser or account")
                val clock=System.currentTimeMillis()
                com.azrael.pixivdumpsync.SourceIndexDb(context).use{index->
                    index.add(address,selected.label,area,"native")
                    for(hit in response.hits){
                        if(stop())break
                        val thumb=hit.preview?.takeIf{
                            com.azrael.pixivdumpsync.SearchRanking.validUrl(it)
                        }
                        if(index.upsert(com.azrael.pixivdumpsync.IndexedItem(
                            hit.url,hit.title,thumb,address,area,"video",clock,false)))
                            count++
                    }
                    // A checked page is evidence of discovery only.
                    index.update(address,response.next,null,count,
                        response.next==null)
                }
                cursor=response.next
                db.setBackfill(selected.id,cursor,cursor==null)
                db.checked(selected.id,null)
                if(pageIndex==1 && pages.size==1 && cursor!=null && cursor !in seen)
                    pages+=cursor
                else if(pageIndex>1 && cursor!=null && cursor !in seen)
                    pages+=cursor
            }
            if(stop())throw InterruptedException("Stopped by user")
        }finally{db.close()}
    }
    fun submit(context: Context, scan: Boolean, targetHost:String?=null,
               onStatus: (String)->Unit = {}) =
        executor.submit { work(context,scan,onStatus,targetHost) }
    private fun downloadOne(context: Context, db: VideoDb, item: Video, status:(Int)->Unit): String {
        // Native yt-dlp may run in a read-only working directory on Android.
        // Force every output and .part file into a writable private staging area.
        val parent=File(context.cacheDir,"videoflow-staging/${item.id}")
        if(!parent.isDirectory && !parent.mkdirs())
            throw java.io.IOException("Cannot create private download staging directory")
        val probe=File.createTempFile(".write-probe",null,parent)
        check(probe.delete()){"Cannot clean up download staging probe"}
        val template=File(parent,"%(title).160s [%(id)s].%(ext)s").absolutePath
        val stage=parent.absolutePath
        // The embedded Python yt-dlp process still produced relative .part
        // names despite --output. Android's default process working directory
        // is read-only. chdir prevents the confirmed EROFS error even if the
        // underlying yt-dlp wrapper drops a path override.
        AsterionCwd.enter(parent)
        val prefs=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        val viaProxy=prefs.getBoolean("tor_proxy",false)
        val hidden=Uri.parse(item.url).host.orEmpty().endsWith(".onion",true)
        require(!hidden||viaProxy){"Configure a SOCKS proxy before connecting to this source"}
        val cookie=File(context.filesDir,"videoflow/cookies.txt")
        require(WebDiscovery.isUrl(item.url)){"Only public HTTP(S) video URLs are supported"}
        val connectedCookies=AuthorizedVideoSession.temporaryCookies(context,item.url)
        fun request(link:String, referer:String?=null)=
            YtDlpRequest(link).setOutputTemplate(template)
                // An explicit output path overrides the wrapper's relative default.
                .addOption("--paths","home:"+stage)
                .addOption("--paths","temp:"+stage)
                .addOption("--output",template)
                .addOption("--no-cache-dir")
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
        fun preflight(link:String,referer:String?=null):
                Pair<Long,FullLengthVideoPolicy.Decision> {
            // Verify runtime before requesting a single media fragment; video
            // resolution alone cannot establish that a scene is complete.
            val request=YtDlpRequest(link)
                .addOption("--no-cache-dir")
                .addOption("--paths","home:"+stage)
                .addOption("--dump-single-json")
                .addOption("--skip-download")
                .addOption("--no-playlist")
                .addOption("--socket-timeout","20")
                .apply{
                    if(viaProxy && hidden)addOption("--proxy",
                        "socks5h://127.0.0.1:${prefs.getInt("tor_port",9050)}")
                    if(!referer.isNullOrBlank())addOption("--referer",referer)
                    val selected=connectedCookies?.takeIf{it.isFile&&it.length()>0}
                        ?:cookie.takeIf{it.isFile&&it.length()>0}
                    if(selected!=null)addOption("--cookies",selected.absolutePath)
                }
            val response=YtDlp.execute(request){_,_,_->}
            if(!response.isSuccess)error("Cannot inspect complete-video metadata")
            val text=response.output.lineSequence().map{it.trim()}
                .firstOrNull{it.startsWith("{")&&it.endsWith("}")}
                ?:error("Video extractor returned no metadata JSON")
            val json=JSONObject(text)
            val duration=json.optDouble("duration",0.0)
                .takeIf{it.isFinite() && it>0.0}?.toLong()
            val extracted=json.optString("title","")
            val expected=FullSceneRuntimeDb(context).use{db->
                listOfNotNull(db.expected(item.title),db.expected(extracted)).maxOrNull()
            }
            val decision=FullLengthVideoPolicy.assess(item.title,
                extracted,link,duration,expected)
            // Collect comparison evidence even for held videos, without granting
            // full-length status to an uncorroborated provider's own metadata.
            if(duration!=null){
                val newReference=FullSceneRuntimeDb(context).use{store->
                    val fromItem=store.remember(item.title,link,duration)
                    val fromPage=if(extracted!=item.title)
                        store.remember(extracted,link,duration) else false
                    fromItem || fromPage
                }
                // A newly observed user-designated full original can resolve
                // earlier held uploads on general-purpose platforms.
                if(newReference){
                    db.requeueHeldByTitle(item.title)
                    if(extracted!=item.title)db.requeueHeldByTitle(extracted)
                }
            }
            return (duration?:0L) to decision
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
        var expectedDuration:Long?=null
        var heldReason:String?=null
        try {
            for(candidate in variants.distinct()) {
                val checked=runCatching{preflight(candidate)}
                if(checked.isSuccess){
                    val decision=checked.getOrThrow().second
                    if(decision.verdict!=FullLengthVideoPolicy.Verdict.ACCEPT){
                        heldReason=decision.reason
                        continue
                    }
                    try {
                        execute(candidate)
                        expectedDuration=checked.getOrThrow().first
                        downloaded=true
                        break
                    } catch(t:Exception){last=t}
                }else last=checked.exceptionOrNull()
                val media=if(viaProxy)null else runCatching{
                    WebDiscovery.directMedia(candidate)}.getOrNull()
                if(!media.isNullOrBlank()&&media!=candidate) {
                    val checkedMedia=runCatching{preflight(media,candidate)}
                    if(checkedMedia.isSuccess){
                        val decision=checkedMedia.getOrThrow().second
                        if(decision.verdict!=FullLengthVideoPolicy.Verdict.ACCEPT){
                            heldReason=decision.reason
                            continue
                        }
                        try {
                            execute(media,candidate)
                            expectedDuration=checkedMedia.getOrThrow().first
                            downloaded=true
                            break
                        } catch(t:Exception){last=t}
                    }else last=checkedMedia.exceptionOrNull()
                }
            }
        } finally {
            // Do not keep browser authentication in the file system after transfer.
            connectedCookies?.delete()
        }
        if(!downloaded){
            if(heldReason!=null)throw FullVersionUnavailable(heldReason!!)
            throw (last?:IllegalStateException("No video extractor succeeded"))
        }
        val file=parent.listFiles()?.filter { it.isFile && it.length()>0 &&
            it.extension.lowercase() in setOf("mp4","mkv","webm","mov","ts","m4a","mp3","flac","opus") }
            ?.maxByOrNull { it.length() } ?: error("No completed playable video found")
        val audioOnly=prefs.getString("quality","")=="bestaudio/best"
        // Do not publish a truncated/incomplete local file even if the
        // remote page advertised a longer runtime.
        val actual=runCatching{
            val retriever=MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()?.div(1000L)
            }finally{retriever.release()}
        }.getOrNull()
        val promised=expectedDuration?:throw FullVersionUnavailable(
            "Full-version runtime was lost before local verification")
        if(actual==null || actual+5L<promised){
            file.delete()
            throw FullVersionUnavailable(
                "Incomplete downloaded runtime: ${actual?:0}s of ${promised}s")
        }
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
        VideoEngine.submit(applicationContext,
            intent?.getBooleanExtra("scan",false)==true,
            intent?.getStringExtra("target_site_host")) { text ->
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
