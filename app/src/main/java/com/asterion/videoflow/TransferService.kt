package com.asterion.videoflow

import android.app.*
import android.content.*
import android.os.*
import android.provider.MediaStore
import android.os.Environment
import android.net.Uri
import android.media.MediaMetadataRetriever
import com.azrael.galleryflow.SiteSession
import java.io.File
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.ffmpegkit_maintained.ytdlp.YtDlp

class FullVersionUnavailable(message:String):Exception(message)

object VideoEngine {
    private val busy=AtomicBoolean(false)
    private val executor=Executors.newSingleThreadExecutor()
    private val stopRequests=java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private val extractionJobs=java.util.concurrent.ConcurrentHashMap<Long,
        java.util.concurrent.Future<*>>()
    private val metadataExecutor=Executors.newSingleThreadExecutor{task->
        Thread(task,"videoflow-native-metadata").apply{isDaemon=true}
    }
    private val transferExecutor=Executors.newSingleThreadExecutor{task->
        Thread(task,"videoflow-native-transfer").apply{isDaemon=true}
    }
    /** Cancels an individual video without deleting its source, metadata or cache.
     * A stopped transfer can be explicitly resumed in Downloads.
     */
    fun pauseVideo(context:Context,id:Long){
        stopRequests.add(id)
        VideoDb(context).use{it.pause(id)}
        extractionJobs[id]?.cancel(true)
    }
    fun resumeVideo(context:Context,id:Long){
        stopRequests.remove(id)
        VideoDb(context).use{it.retry(id)}
        launch(context,false)
    }
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
        // Attempt each independent process even if the other one fails.
        // A previously swallowed secondary-process exception silently reduced
        // the engine to one downloader on Android's FGS-restricted paths.
        val primaryStarted=try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)
            else context.startService(intent)
            true
        }catch(_:Exception){
            if(allowJobFallback)
                Scheduling.requestPrimaryImmediate(context,scan,targetHost)
            false
        }
        val secondaryStarted=try{
            val second=Intent(context,TransferAgentTwoService::class.java)
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(second)
            else context.startService(second)
            true
        }catch(_:Exception){
            if(allowJobFallback)Scheduling.requestSecondaryImmediate(context)
            false
        }
        // The primary PollJobService starts its own work() when FGS startup
        // was denied; secondary also has an independent secondary-process job.
        // Either isolated process can continue transferring on its own.
        // Do not report a failed launch when the second process started.
        return primaryStarted || secondaryStarted
    }
    fun work(context: Context, scan: Boolean, onStatus: (String)->Unit = {},
             targetHost:String?=null) {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return
        if(!busy.compareAndSet(false,true)) return
        com.azrael.pixivdumpsync.BackfillPerformance.backgroundWorker()
        try {
            val db=VideoDb(context)
            db.recover()
            try {
                YtDlp.init(context.applicationContext)
                EmbeddedYtDlpMetadata.checkRuntime()
                VideoRuntimeState.healthy(context)
                // Old releases converted each temporary interpreter startup
                // failure into a permanent file failure. Recover only those
                // precise records, preserving user pauses and completed files.
                db.requeuePythonStartupFailures()
            }catch(t:Throwable){
                if(t is VirtualMachineError || t is ThreadDeath)throw t
                // Do not poison another queued original every time a service
                // attempts to start Python. Record the real cause chain once.
                val cause=VideoRuntimeState.failed(context,t)
                onStatus("Video runtime unavailable: "+cause.take(75))
                return
            }
            fun drainQueued(maxItems:Int) {
                var transferred=0
                val deadline=android.os.SystemClock.elapsedRealtime()+6L*60_000
                while(com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                      !Thread.currentThread().isInterrupted && transferred<maxItems &&
                      android.os.SystemClock.elapsedRealtime()<deadline) {
                val next=db.claimNext() ?: break
                transferred++
                onStatus("Downloading ${next.title}")
                // Atomic claim was already persisted by VideoDb; repeating a
                // blind state update could steal a paused or replaced job.
                VideoTransferJournal.record(context,next.id,"preparing")
                try {
                    val uri=downloadOne(context,db,next) { percent ->
                        onStatus("${next.title}: $percent%")
                    }
                    if(stopRequests.contains(next.id))
                        throw java.util.concurrent.CancellationException("Paused")
                    if(!db.completeClaim(next.id,next.lease,uri)){
                        runCatching{
                            context.contentResolver.delete(android.net.Uri.parse(uri),null,null)
                        }
                        throw java.util.concurrent.CancellationException(
                            "Transfer cancelled or superseded before final receipt")
                    }
                    VideoTransferJournal.record(context,next.id,"saved")
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
                } catch(t:Throwable) {
                    // Preserve VM-fatal conditions, but convert extractor JNI
                    // linkage errors into a durable per-file error instead of
                    // crashing the process and stranding all other transfers.
                    if(t is VirtualMachineError || t is ThreadDeath)throw t
                    if(!db.ownsClaim(next.id,next.lease)){
                        // Another process, pause or restart owns the current
                        // state. Do not overwrite that state from this worker.
                        stopRequests.remove(next.id)
                        onStatus("Transfer claim released: "+next.title)
                        continue
                    }
                    if(stopRequests.remove(next.id) ||
                        t is java.util.concurrent.CancellationException){
                        db.pause(next.id)
                        VideoTransferJournal.record(context,next.id,"paused")
                        onStatus("Paused video: "+next.title)
                    }else if(t is FullVersionUnavailable) {
                        db.update(next.id,"full_only_hold",error=t.message)
                        VideoTransferJournal.record(context,next.id,"held")
                        onStatus("Full version unverified: ${next.title}")
                    }else{
                        val reason=t.message?:t.javaClass.simpleName
                        db.scheduleRetry(next.id,reason)
                        VideoTransferJournal.record(context,next.id,"retry",
                            VideoFailurePolicy.classify(reason).name)
                        onStatus("Failed ${next.title}")
                    }
                }
            }
            }

            // Transfer queued media before any potentially slow provider scan.
            drainQueued(if(scan)6 else 12)
            if(scan) {
                // Independent name crawlers keep their own scheduling and do
                // not block the foreground download queue in this worker.
                // Independent per-provider discovery agents run concurrently.
                // Downloads still use their durable shared queue to avoid OOM.
                val eligible=db.listWatches().filter {
                    it.enabled && com.azrael.pixivdumpsync.SiteControls.any(context,it.address) &&
                    (targetHost==null ||
                        com.azrael.pixivdumpsync.SiteControls.key(it.address)==targetHost)
                }
                val selectedWatches=if(targetHost!=null || eligible.size<=6)eligible else {
                    val progress=context.getSharedPreferences("asterion_video_scan_rotation",0)
                    val index=progress.getInt("cursor",0).coerceAtLeast(0)%eligible.size
                    progress.edit().putInt("cursor",(index+6)%eligible.size).apply()
                    (0 until 6).map{eligible[(index+it)%eligible.size]}
                }
                selectedWatches.forEach{selected->
                    // Per-provider discovery owns its own DB connection. The
                    // transfer worker no longer waits on slow/blocked sites.
                    com.azrael.pixivdumpsync.BackfillAgents.submit(
                        context,"videoflow:"+selected.address){
                        VideoDb(context).use{db->
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
                            val latest=WebDiscovery.page(watch.address,watch.address,context)
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
                                val page=WebDiscovery.page(cursor,watch.address,context)
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
                        // Discovery and transfer run asynchronously. Enqueued
                        // originals wake a dedicated transfer pass instead of
                        // retaining the shared worker until all sites finish.
                        if(com.azrael.pixivdumpsync.AppPower.enabled(context))
                            Scheduling.requestImmediate(context,false)
                    }
                }
            }
            if(scan)drainQueued(4)
            if(db.next()!=null)onStatus("Queued downloads remain; next worker pass scheduled")
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
        } finally {
            busy.set(false)
            if(com.azrael.pixivdumpsync.AppPower.enabled(context)) {
                val pending=runCatching { VideoDb(context).use{it.next()!=null} }
                    .getOrDefault(false)
                if(pending)Scheduling.requestImmediate(context,false)
                val due=runCatching{
                    VideoDb(context).use{it.nextRetryDelayMs()}
                }.getOrNull()
                if(due!=null)Scheduling.requestRetry(context,due)
            }
        }
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
            var enqueued=0
            val existing=com.azrael.pixivdumpsync.SourceIndexDb(context).use{db->
                db.sources().firstOrNull{it.url==address}
            }
            var pageIndex=0
            while(pageIndex<pages.size && pageIndex<8 && !stop() &&
                com.azrael.pixivdumpsync.AppPower.enabled(context)){
                val page=pages[pageIndex++]
                if(!seen.add(page))continue
                val response=try { WebDiscovery.page(page,selected.address,context) }
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
                        val genuine=com.azrael.pixivdumpsync.IndexedMediaQuality.isMedia(
                            hit.url,hit.title,thumb,area) ||
                            (WebDiscovery.isVideoPage(hit.url) &&
                                !com.azrael.pixivdumpsync.IndexedMediaQuality.navigationTitle(
                                    hit.title))
                        if(!genuine)continue
                        if(index.upsert(com.azrael.pixivdumpsync.IndexedItem(
                            hit.url,hit.title,thumb,address,area,"video",clock,false)))
                            count++
                        // Explicit Backfill means obtaining originals, not simply
                        // indexing them; preserve each file's own transfer status.
                        // Manual Backfill is independent of automatic Sync.
                        // Attach explicit user-requested files to watch_id=0;
                        // otherwise a disabled Sync switch would silently block
                        // the videos that Backfill itself discovered.
                        if(db.addVideo(hit.url,hit.title,0L))enqueued++
                    }
                    if(stop())throw InterruptedException("Provider backfill stopped")
                    // A checked page is discovery evidence, not download success.
                    index.update(address,response.next,null,
                        (existing?.discovered?:0)+count,response.next==null)
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
            if(enqueued>0)launch(context,false)
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
        try {
        val prefs=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        val viaProxy=prefs.getBoolean("tor_proxy",false)
        val hidden=Uri.parse(item.url).host.orEmpty().endsWith(".onion",true)
        require(!hidden||viaProxy){"Configure a SOCKS proxy before connecting to this source"}
        val cookie=File(context.filesDir,"videoflow/cookies.txt")
        require(WebDiscovery.isUrl(item.url)){"Only public HTTP(S) video URLs are supported"}
        val connected=SiteSession.bound(context,item.url)
        // Only a genuinely user-selected queued row may use the manual
        // source-version exception to the automatic full-scene policy.
        val userRequested=db.requestOrigin(item.id)=="user"
        val connectedCookies=AuthorizedVideoSession.temporaryCookies(context,item.url)
        // The Eporner watch page can serve a verification interstitial even
        // though the official embed exposes the publisher's actual player.
        // Only use that alternate route after the user explicitly connects
        // an authorized site session. The public official API attests runtime.
        val publishedRuntime=if(connected &&
            EpornerOriginalSource.videoId(item.url)!=null)
            EpornerOriginalSource.officialDuration(item.url) else null
        val preparationStart=android.os.SystemClock.elapsedRealtime()
        check(db.touchClaim(item.id,item.lease)){
            "Download claim is no longer owned by this worker"
        }
        // The third-party Java request wrapper silently discards several
        // essential yt-dlp options (including its output paths). Invoke the
        // embedded Python YoutubeDL API with typed options instead. The
        // staged bytes are measured while the future is executing, not inferred
        // from a progress callback which can remain at zero indefinitely.
        fun executeControlled(link:String,referer:String?=null) {
            if(stopRequests.contains(item.id))
                throw java.util.concurrent.CancellationException("Video paused")
            val selected=connectedCookies?.takeIf{it.isFile&&it.length()>0}
                ?:cookie.takeIf{it.isFile&&it.length()>0}
            val proxy=if(viaProxy && hidden)
                "socks5h://127.0.0.1:${prefs.getInt("tor_port",9050)}"
                else null
            val quality=prefs.getString("quality","best[ext=mp4]/best")
                ?: "best[ext=mp4]/best"
            // Direct, browser-accessible media uses the Kotlin/Android HTTP
            // streaming lane. No Python startup, extractor wrapper or shell.
            // Page URLs and HLS/DASH still use yt-dlp for authorized extraction.
            val native=NativeMediaDownloader.isNativeEligible(item.url,link) &&
                !hidden && !viaProxy
            val future=transferExecutor.submit<Int>{
                if(native) {
                    val sameHost=runCatching{
                        java.net.URI(item.url).host.equals(
                            java.net.URI(link).host,ignoreCase=true)
                    }.getOrDefault(false)
                    NativeMediaDownloader.download(link,referer,parent,
                        cookieHeader=if(sameHost)
                            SiteSession.cookie(link) else null,
                        filenameStem=item.title+" ["+item.id+"]",
                        cancelled={
                            stopRequests.contains(item.id) ||
                            Thread.currentThread().isInterrupted
                        })
                    0
                } else EmbeddedYtDlpTransfer.download(
                    link,referer,selected,proxy,parent,template,quality)
            }
            extractionJobs[item.id]=future
            val start=android.os.SystemClock.elapsedRealtime()
            var latestBytes=parent.listFiles()?.filter{it.isFile}
                ?.sumOf{it.length()}?:0L
            var lastByteTick=start
            var lastLeaseTick=0L
            VideoTransferJournal.record(context,item.id,"transfer")
            try{
                while(true){
                    if(stopRequests.contains(item.id) ||
                        Thread.currentThread().isInterrupted)
                        throw java.util.concurrent.CancellationException("Video paused")
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(now-lastLeaseTick>=4_000L){
                        if(!db.touchClaim(item.id,item.lease))
                            throw java.util.concurrent.CancellationException(
                                "Transfer paused or claim superseded")
                        lastLeaseTick=now
                    }
                    val current=parent.listFiles()?.filter{it.isFile}
                        ?.sumOf{it.length()}?:0L
                    if(current>latestBytes){
                        latestBytes=current
                        lastByteTick=now
                        VideoTransferJournal.record(
                            context,item.id,"receiving",current.toString())
                    }
                    val reason=VideoTransferLimits.expired(
                        "transfer",now-start,now-lastByteTick)
                    if(reason!=null)
                        throw java.util.concurrent.TimeoutException(reason)
                    try{
                        val exit=future.get(VideoTransferLimits.POLL_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                        if(exit!=0)throw java.io.IOException(
                            "Embedded video extractor ended with status "+exit)
                        return
                    }catch(_:java.util.concurrent.TimeoutException){
                        // Re-read real written bytes and enforce deadlines.
                    }
                }
            }catch(t:Exception){
                future.cancel(true)
                throw t
            }finally{
                extractionJobs.remove(item.id,future)
            }
        }
        fun preflight(link:String,referer:String?=null):
                Pair<Long,FullLengthVideoPolicy.Decision> {
            // The installed yt-dlp wrapper ignores --dump-single-json and
            // --skip-download and *always* reports blank output. Call the
            // already-embedded Chaquopy yt_dlp API directly with download=false.
            // No file or media fragment is downloaded during this preflight.
            val selected=connectedCookies?.takeIf{it.isFile&&it.length()>0}
                ?:cookie.takeIf{it.isFile&&it.length()>0}
            val proxy=if(viaProxy && hidden)
                "socks5h://127.0.0.1:${prefs.getInt("tor_port",9050)}"
                else null
            val future=metadataExecutor.submit<JSONObject>{
                EmbeddedYtDlpMetadata.inspect(link,referer,selected,proxy)
            }
            extractionJobs[item.id]=future
            val started=android.os.SystemClock.elapsedRealtime()
            lateinit var json:JSONObject
            try{
                while(true){
                    if(stopRequests.contains(item.id) ||
                        Thread.currentThread().isInterrupted)
                        throw java.util.concurrent.CancellationException("Video paused")
                    if(!db.touchClaim(item.id,item.lease))
                        throw java.util.concurrent.CancellationException("Lease released")
                    if(android.os.SystemClock.elapsedRealtime()-started>=
                        VideoTransferLimits.PREFLIGHT_MS)
                        throw java.util.concurrent.TimeoutException(
                            "Metadata inspection timed out before downloading")
                    try{
                        json=future.get(VideoTransferLimits.POLL_MS,
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                        break
                    }catch(_:java.util.concurrent.TimeoutException){
                        // Keep the lease alive and enforce a deadline.
                    }
                }
            }catch(t:Exception){
                future.cancel(true)
                throw t
            }finally{
                extractionJobs.remove(item.id,future)
            }
            val duration=json.optDouble("duration",0.0)
                .takeIf{it.isFinite() && it>0.0}?.toLong()
            val extracted=json.optString("title","")
            val expected=publishedRuntime ?: FullSceneRuntimeDb(context).use{db->
                listOfNotNull(db.expected(item.title),db.expected(extracted)).maxOrNull()
            }
            val decision=FullLengthVideoPolicy.assess(item.title,
                extracted,link,duration,expected,
                publisherVerified=publishedRuntime!=null,
                userExplicitlyRequested=userRequested)
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
            executeControlled(link,referer)
            val received=parent.listFiles()?.filter{it.isFile}
                ?.sumOf{it.length()}?:0L
            if(received<=0L)
                throw java.io.IOException("No original media bytes received")
            // Saved=100% is set only after the verified MediaStore receipt.
        }
        // For Eporner use the verified publisher's own embed first. Never
        // silently bypass an age/login requirement or send cookies cross-site.
        // Other providers retain existing canonical-URL behavior.
        val variants=(EpornerOriginalSource.videoCandidates(item.url,connected)+
            // UncensoredHentai's watch pages use an authorized native iframe
            // player. yt-dlp cannot extract the WordPress watch page itself.
            // Resolve only a page-owned, explicitly supported player embed.
            (if(viaProxy)emptyList() else runCatching{
                WebDiscovery.embeddedCandidates(item.url)
            }.getOrDefault(emptyList()))).distinct()
        val watchPorn=if(viaProxy)null else runCatching{
            WatchPornOriginalSource.resolve(item.url)
        }.getOrNull()
        val freePornVideos=if(viaProxy)null else runCatching{
            FreePornVideosOriginalSource.resolve(item.url)
        }.getOrNull()
        var last:Throwable?=null
        var downloaded=false
        var expectedDuration:Long?=null
        var heldReason:String?=null
        try {
            if(freePornVideos!=null){
                // Direct publisher MP4, not an unrelated iframe or advertised clip.
                // A missing publisher runtime still requires preflight; this
                // never invents a full-length verification result.
                val published=freePornVideos.durationSeconds
                val verified=if(published!=null){
                    val result=FullLengthVideoPolicy.assess(
                        item.title,item.title,item.url,published,published,
                        publisherVerified=true)
                    published to result
                }else runCatching{
                    preflight(freePornVideos.mediaUrl,item.url)
                }.getOrElse{error->
                    last=error
                    0L to FullLengthVideoPolicy.Decision(
                        FullLengthVideoPolicy.Verdict.HOLD_FOR_REFERENCE,
                        "Direct video duration was not available")
                }
                if(verified.second.verdict==FullLengthVideoPolicy.Verdict.ACCEPT &&
                    verified.first>0L){
                    try{
                        execute(freePornVideos.mediaUrl,item.url)
                        expectedDuration=verified.first
                        downloaded=true
                    }catch(t:Exception){last=t}
                }else heldReason=verified.second.reason
            }
            if(watchPorn!=null){
                // Full runtime was published twice in the provider's own page.
                // This avoids a pointless 50-second metadata preflight for a
                // signed direct MP4 that has no extractor-reported duration.
                // Final device-side runtime is still checked below.
                val decision=FullLengthVideoPolicy.assess(
                    item.title,item.title,item.url,
                    watchPorn.durationSeconds,watchPorn.durationSeconds,
                    publisherVerified=true)
                if(decision.verdict==FullLengthVideoPolicy.Verdict.ACCEPT){
                    try{
                        execute(watchPorn.mediaUrl,item.url)
                        expectedDuration=watchPorn.durationSeconds
                        downloaded=true
                    }catch(t:Exception){last=t}
                }else heldReason=decision.reason
            }
            if(!downloaded) for(candidate in variants.distinct()) {
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
        if(stopRequests.contains(item.id))
            throw java.util.concurrent.CancellationException("Video paused")
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
        } finally {
            runCatching { AsterionCwd.enter(context.filesDir) }
        }
    }
}

class TransferService: Service() {
    private val binder=Binder()
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            VideoEngine.makeChannel(this)
            startForeground(24014,VideoEngine.notification(this,"Preparing downloads"))
        }catch(t:Exception){
            // Android can accept startForegroundService() but refuse the
            // subsequent foreground promotion. That used to crash the
            // process after launch() had already reported success.
            runCatching{Scheduling.requestPrimaryImmediate(applicationContext,
                intent?.getBooleanExtra("scan",false)==true,
                intent?.getStringExtra("target_site_host"))}
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
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
