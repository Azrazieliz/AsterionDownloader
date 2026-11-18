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
    // Each selected video runs independently; stale tasks left by earlier
    // timed-out publisher extractors must not reject the next selection.
    // Separate pools retain the native transfer lane when Python is stuck.
    private val metadataExecutor=VideoExecutionPools.create("videoflow-metadata",2)
    private val publisherExecutor=VideoExecutionPools.create("videoflow-publisher",2)
    private val transferExecutor=VideoExecutionPools.create("videoflow-extractor",2)
    private val nativeExecutor=VideoExecutionPools.create("videoflow-native",2)
    /** Keep the Python-specific failures scoped to Python-required media.
     * Previously both download processes exited before reading the queue
     * whenever this optional runtime could not start.
     */
    private fun ensureExtractor(context:Context) {
        try {
            LegacyYtDlpTransfer.initialize(context)
            EmbeddedYtDlpMetadata.checkRuntime()
            VideoRuntimeState.healthy(context)
            VideoDb(context).use{it.requeuePythonStartupFailures()}
        }catch(t:Throwable){
            if(t is VirtualMachineError || t is ThreadDeath)throw t
            val cause=VideoRuntimeState.failed(context,t)
            throw IllegalStateException(
                "Video extractor unavailable: "+cause.take(180),t)
        }
    }
    const val ACTION_CANCEL_LOCAL="com.azrael.pixivdumpsync.action.VIDEO_CANCEL_LOCAL"
    const val EXTRA_VIDEO_ID="video_id"

    /** Stop extraction in the same process as the owning Future. */
    fun cancelInThisProcess(id:Long){
        val active=extractionJobs[id]?:return
        stopRequests.add(id)
        active.cancel(true)
    }
    private fun signalCancellation(context:Context,id:Long){
        cancelInThisProcess(id)
        for(type in listOf(VideoCancelPrimaryReceiver::class.java,
                VideoCancelSecondaryReceiver::class.java)){
            context.sendBroadcast(Intent(context,type).setAction(ACTION_CANCEL_LOCAL)
                .putExtra(EXTRA_VIDEO_ID,id))
        }
    }
    fun pauseVideo(context:Context,id:Long){
        // Only cancel if an eligible active/queued row was actually paused.
        // A stale button must not interrupt a completed or dismissed video.
        val changed=VideoDb(context).use{it.pause(id)}
        if(changed)signalCancellation(context,id)
    }
    fun cancelVideo(context:Context,id:Long):Boolean{
        val changed=VideoDb(context).use{it.dismiss(id)}
        signalCancellation(context,id)
        return changed
    }
    fun clearUnfinished(context:Context):Int{
        val ids=VideoDb(context).use{it.runningIds()}
        val result=VideoDb(context).use{it.dismissUnfinished()}
        ids.forEach{signalCancellation(context,it)}
        return result
    }
    fun resumeVideo(context:Context,id:Long){
        // A tap on Running/Saved is NOT a retry: do not clear a live
        // worker's stop signal, steal its lease or start a second writer.
        val changed=VideoDb(context).use{it.retry(id)}
        if(changed){
            stopRequests.remove(id)
            launch(context,false)
        }
    }
    const val CHANNEL="videoflow-transfers"
    fun makeChannel(context: Context) {
        val channel=NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
    fun notification(context: Context, message: String): Notification {
        makeChannel(context)
        val open=PendingIntent.getActivity(context, 0, Intent(context,com.azrael.pixivdumpsync.AsterionHomeActivity::class.java)
                .putExtra("asterion_page","Queue"),
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
        var primaryScheduled=false
        val primaryStarted=try{
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)
            else context.startService(intent)
            true
        }catch(_:Exception){
            if(allowJobFallback)
                primaryScheduled=Scheduling.requestPrimaryImmediate(
                    context,scan,targetHost)
            false
        }
        var secondaryScheduled=false
        val secondaryStarted=try{
            val second=Intent(context,TransferAgentTwoService::class.java)
            if(Build.VERSION.SDK_INT>=26)context.startForegroundService(second)
            else context.startService(second)
            true
        }catch(_:Exception){
            if(allowJobFallback)
                secondaryScheduled=Scheduling.requestSecondaryImmediate(context)
            false
        }
        // Android may defer foreground starts while successfully accepting
        // the durable JobScheduler fallback. The old return value discarded
        // successful job scheduling and falsely told users nothing started.
        return primaryStarted || secondaryStarted ||
            primaryScheduled || secondaryScheduled
    }
    fun work(context: Context, scan: Boolean, onStatus: (String)->Unit = {},
             targetHost:String?=null) {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return
        if(!busy.compareAndSet(false,true)){
            DownloaderEventJournal.record(context,0L,"worker_busy","existing_worker_active")
            return
        }
        DownloaderEventJournal.record(context,0L,"worker_started",
            if(scan)"scan_requested" else "manual_or_retry")
        // The isolated second process can execute a different webpage or
        // Python original concurrently: process-global yt-dlp CWD, Python
        // modules and native library state are NOT shared with agent one.
        // Under actual low-memory pressure it falls back to native-only.
        com.azrael.pixivdumpsync.BackfillPerformance.backgroundWorker()
        try {
            val db=VideoDb(context)
            db.recover()
            db.recoverTrustedPolicyHoldsOnce()
            // Do not silently restart the bulk video queues generated by
            // older versions. The existing rows and partial files remain;
            // RESUME is explicit authorization for an individual video.
            db.holdUnrequestedWebsiteVideos()
            // Never gate native HTTP originals on Python availability.
            // Start the embedded extractor lazily only for actual page
            // metadata/extraction work. A broken Python runtime may hold
            // extractor jobs, but direct original bytes can still transfer.
            fun drainQueued(maxItems:Int) {
                var transferred=0
                val deadline=android.os.SystemClock.elapsedRealtime()+6L*60_000
                while(com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                      !Thread.currentThread().isInterrupted && transferred<maxItems &&
                      android.os.SystemClock.elapsedRealtime()<deadline) {
                // The admission decision changes as other downloads release
                // memory. A single snapshot on worker startup could lock agent
                // two into native-only mode for the entire six-minute drain.
                val nativeOnly=VideoAgentAdmission.nativeOnly(context)
                val next=db.claimNext(nativeOnly=nativeOnly)
                if(next==null){
                    // Queued rows with no eligible claim are blocked or held,
                    // not actively downloading. Persist a scheduler signal.
                    if(db.count("queued")>0)
                        DownloaderEventJournal.record(context,0L,"queue_blocked",
                            if(nativeOnly)"native_only_ineligible" else "no_eligible_claim")
                    break
                }
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
                    // The MediaStore receipt was committed already. A cloud
                    // handoff failure must not mark that actual saved original
                    // as FAILED, nor restart its download on the next retry.
                    if(com.azrael.pixivdumpsync.NduSettings.configured(context)){
                        runCatching{
                            com.azrael.pixivdumpsync.NduCloudQueue(context).use{queue->
                                queue.enqueue("videos",
                                    android.net.Uri.parse(uri).lastPathSegment.orEmpty()
                                        .ifBlank{"video_"+next.id+".mp4"},
                                    android.net.Uri.parse(uri))
                            }
                            com.azrael.pixivdumpsync.NduUploadService.launch(context)
                        }.onFailure{
                            android.util.Log.w("AsterionVideo",
                                "Verified local original saved; cloud upload pending",it)
                        }
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
                    if(stopRequests.remove(next.id)){
                        db.pause(next.id)
                        VideoTransferJournal.record(context,next.id,"paused")
                        onStatus("Paused video: "+next.title)
                    }else if(t is java.util.concurrent.CancellationException ||
                        Thread.currentThread().isInterrupted){
                        db.workerInterrupted(next.id,next.lease)
                        VideoTransferJournal.record(context,next.id,"worker_interrupted")
                        onStatus("Download worker interrupted; saved work retained")
                        Thread.currentThread().interrupt()
                        break
                    }else if(t is FullVersionUnavailable) {
                        db.update(next.id,"full_only_hold",error=t.message)
                        VideoTransferJournal.record(context,next.id,"held")
                        onStatus("Full version unverified: ${next.title}")
                    }else{
                        val host=runCatching{
                            java.net.URI(next.url).host.orEmpty()
                        }.getOrDefault("Video provider")
                        val reason=host+": "+VideoErrorDetail.summary(t)
                        val failedAt=VideoTransferJournal.current(context,next.id)?.stage
                            ?:"unknown"
                        db.scheduleRetry(next.id,reason)
                        DownloaderEventJournal.record(context,next.id,"failure_classified",
                            VideoFailurePolicy.classify(reason).name)
                        VideoTransferJournal.record(context,next.id,"failed",
                            failedAt+"/"+VideoFailurePolicy.classify(reason).name+": "+reason)
                        onStatus("Failed ${next.title}")
                    }
                }
            }
            }

            // Transfer queued media before any potentially slow provider scan.
            drainQueued(if(scan)6 else 12)
            // Backpressure protects direct transfers from unbounded automatic
            // discovery. Nothing is erased, and no source cursor is advanced
            // while waiting for the existing queue to drain. Explicit manual
            // site Backfill remains independently available.
            if(scan && (targetHost!=null || db.count("queued")<96)) {
                // Independent name crawlers keep their own scheduling and do
                // not block the foreground download queue in this worker.
                // Independent per-provider discovery agents run concurrently.
                // Downloads still use their durable shared queue to avoid OOM.
                val eligible=db.listWatches().filter {
                    val chosen=targetHost!=null &&
                        com.azrael.pixivdumpsync.SiteControls.key(it.address)==targetHost
                    chosen || (targetHost==null && it.enabled &&
                        com.azrael.pixivdumpsync.SiteControls.sync(context,it.address))
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
                                watch.address,watch.address,context)
                            val collected=linkedMapOf<String,VideoHit>()
                            for(hit in latest.hits)collected.putIfAbsent(hit.url,hit)
                            val backfill=!watch.backfillComplete &&
                                (targetHost!=null ||
                                    com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address))
                            var cursor=if(backfill)watch.cursor?:latest.next else null
                            val visited=hashSetOf(watch.address)
                            var pages=0
                            while(cursor!=null && pages<8 && visited.add(cursor) &&
                                com.azrael.pixivdumpsync.AppPower.enabled(context) &&
                                !Thread.currentThread().isInterrupted){
                                val history=WebDiscovery.watchPornArtistPage(
                                    watch.address,cursor,context)
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
                                (targetHost!=null ||
                                    com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address))
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
                                (targetHost!=null ||
                                    com.azrael.pixivdumpsync.SiteControls.backfill(context,watch.address)) &&
                                !Thread.currentThread().isInterrupted)
                                db.setBackfill(watch.id,cursor,cursor==null)
                            collected.values.toList()
                        }
                        if(!com.azrael.pixivdumpsync.AppPower.enabled(context) ||
                            Thread.currentThread().isInterrupted)break
                        if(targetHost==null &&
                            !com.azrael.pixivdumpsync.SiteControls.sync(context,watch.address))continue
                        if(hits.isEmpty())error("No video entries indexed from this provider; a site-specific parser or login may be required")
                        // Sync and Backfill index metadata; they do not
                        // authorize downloading every discovered video.
                        // Explicitly selected videos still use VideoDb.
                        val foundForReview=hits.filterNot{
                            FullLengthVideoPolicy.isExplicitPreview(it.title,it.url)
                        }
                                                if(foundForReview.isNotEmpty()){
                            com.azrael.pixivdumpsync.SearchResultsDb(context).use{results->
                                results.save("WATCH:"+watch.label,foundForReview.map{hit->
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
                val pending=runCatching { VideoDb(context).use{
                    it.next(nativeOnly=VideoAgentAdmission.nativeOnly(context))!=null
                } }
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
                        // Historical discovery stores source-owned previews;
                        // transferring any video requires selecting its card.
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
            // No automatic video transfer is triggered by catalog backfill.
        }finally{db.close()}
    }
    fun submit(context: Context, scan: Boolean, targetHost:String?=null,
               onStatus: (String)->Unit = {}) =
        executor.submit { work(context,scan,onStatus,targetHost) }
    private fun downloadOne(context: Context, db: VideoDb, item: Video, status:(Int)->Unit): String {
        // Native yt-dlp may run in a read-only working directory on Android.
        // Force every output and .part file into a writable private staging area.
        val previous=File(context.cacheDir,"videoflow-staging/${item.id}")
        val storage=context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?:context.filesDir
        val durable=File(storage,"videoflow-staging/${item.id}")
        // Android may delete cacheDir between job attempts. Keep the old
        // partials, but copy them into persistent storage before resuming.
        // Never continue streaming into the evictable old cache directory.
        val parent=VideoStaging.prepare(previous,durable)
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
        // The user explicitly removed Tor from Asterion Downloader.
        // Never infer a working relay from the obsolete tor_proxy setting or
        // silently fall back to 127.0.0.1:9050.
        require(!Uri.parse(item.url).host.orEmpty().endsWith(".onion",true)){
            "This unified downloader accepts public publisher HTTPS URLs only"
        }
        NetworkRoute.requireVpn(context,item.url)
        NetworkRoute.requirePublisherExit(context,item.url)
        val canInspectPublisherHtml=true
        val cookie=File(context.filesDir,"videoflow/cookies.txt")
        require(WebDiscovery.isUrl(item.url)){"Only public HTTP(S) video URLs are supported"}
        val connected=SiteSession.bound(context,item.url)
        // Only a genuinely user-selected queued row may use the manual
        // source-version exception to the automatic full-scene policy.
        val userRequested=db.requestOrigin(item.id)=="user"
        val connectedCookies=AuthorizedVideoSession.temporaryCookies(context,item.url)
        try {
        // The Eporner watch page can serve a verification interstitial even
        // though the official embed exposes the publisher's actual player.
        // Only use that alternate route after the user explicitly connects
        // an authorized site session. The public official API attests runtime.
        val publishedRuntime:Long?=null // Trusted-provider confidence; no online duration prerequisite
        val preparationStart=android.os.SystemClock.elapsedRealtime()
        check(db.touchClaim(item.id,item.lease)){
            "Download claim is no longer owned by this worker"
        }
        fun <T> inspectPublisher(inspect:()->T):T {
            val inspectedAt=android.os.SystemClock.elapsedRealtime()
            val future=publisherExecutor.submit<T>{inspect()}
            extractionJobs[item.id]=future
            try {
                while(true){
                    if(stopRequests.contains(item.id) || Thread.currentThread().isInterrupted ||
                        !db.touchClaim(item.id,item.lease))
                        throw java.util.concurrent.CancellationException("Transfer paused or superseded")
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(VideoTransferLimits.preparationBudgetExceeded(
                            now-preparationStart,
                            VideoPayloadBytes.count(parent)>0L))
                        throw java.util.concurrent.TimeoutException(
                            "Total selected-media resolution budget exceeded")
                    val remaining=VideoTransferLimits.NO_BYTES_RESOLVE_MS-
                        (now-inspectedAt)
                    if(remaining<=0L)
                        throw java.util.concurrent.TimeoutException(
                            "Selected publisher did not expose a transferable original "+
                                "within the resolver time budget")
                    try {
                        return future.get(minOf(remaining,VideoTransferLimits.POLL_MS),
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                    }catch(_:java.util.concurrent.TimeoutException){
                        // Keep the download lease alive while the publisher responds.
                    }catch(failure:java.util.concurrent.ExecutionException){
                        throw (failure.cause?:failure)
                    }
                }
            }catch(interrupted:InterruptedException){
                Thread.currentThread().interrupt()
                throw java.util.concurrent.CancellationException("Publisher inspection interrupted")
            }finally{
                future.cancel(true)
                extractionJobs.remove(item.id,future)
                VideoExecutionPools.discardCancelled(publisherExecutor)
            }
        }
        fun <T> publisherResult(inspect:()->T):Result<T> =
            runCatching{inspectPublisher(inspect)}.onFailure{
                if(it is java.util.concurrent.CancellationException ||
                    it is VirtualMachineError || it is ThreadDeath)throw it
            }
        // The third-party Java request wrapper silently discards several
        // essential yt-dlp options (including its output paths). Invoke the
        // embedded Python YoutubeDL API with typed options instead. The
        // staged bytes are measured while the future is executing, not inferred
        // from a progress callback which can remain at zero indefinitely.
        var signedRuntimePrimed=false
        fun executeControlled(link:String,referer:String?=null,
                              verifiedFirstPartyMp4:Boolean=false) {
            if(stopRequests.contains(item.id))
                throw java.util.concurrent.CancellationException("Video paused")
            // A user can connect the selected external video host (VOE,
            // Streamtape or the first-party JAVIDEO player) independently
            // of the publisher. Its own session must be used by yt-dlp;
            // using only the watch-page's cookies cannot open that host.
            // Existing Netscape cookie entries remain scoped to their host.
            val selectedHostCookies=if(SiteSession.bound(context,link) &&
                    runCatching {
                        java.net.URI(item.url).host.orEmpty().lowercase() !=
                            java.net.URI(link).host.orEmpty().lowercase()
                    }.getOrDefault(false))
                AuthorizedVideoSession.temporaryCookies(context,link)
                else null
            val selected=selectedHostCookies?.takeIf{it.isFile&&it.length()>0}
                ?:connectedCookies?.takeIf{it.isFile&&it.length()>0}
                ?:cookie.takeIf{it.isFile&&it.length()>0}
            val proxy=NetworkRoute.extractorProxy(context,item.url)
            val quality=prefs.getString("quality","best[ext=mp4]/best")
                ?: "best[ext=mp4]/best"
            // Direct, browser-accessible media uses the Kotlin/Android HTTP
            // streaming lane. No Python startup, extractor wrapper or shell.
            // Page URLs and HLS/DASH still use yt-dlp for authorized extraction.
            // Android's system VPN is honored by the native HTTPS client.
            // Never depend on the removed legacy Tor/hidden-mode flag.
            val jkNative=JavGuruJkNativeHls.eligible(item.url,link,referer)
            val native=jkNative || NativeMediaDownloader.isNativeEligible(
                item.url,link,verifiedFirstPartyMp4)
            val future=(if(native)nativeExecutor else transferExecutor).submit<Int>{
                if(jkNative) {
                    // Strictly the selected Jav Guru JK finalized AES-128 HLS.
                    // The legacy yt-dlp transport returned 403 on these same
                    // publisher-selected fragments; do not route other
                    // providers or unknown manifests through this native lane.
                    VideoTransferJournal.record(
                        context,item.id,"selected_jk_native_hls")
                    JavGuruJkNativeHls.download(
                        item.url,link,referer,parent,
                        item.title+" ["+item.id+"]",
                        NetworkRoute.javaProxy(context,item.url),
                        progress={percent->
                            if(!db.progressClaim(item.id,item.lease,percent))
                                throw java.util.concurrent.CancellationException(
                                    "Selected JK native transfer lease superseded")
                            status(percent)
                        },
                        cancelled={
                            stopRequests.contains(item.id) ||
                            Thread.currentThread().isInterrupted ||
                            !db.ownsClaim(item.id,item.lease)
                        })
                    0
                }else if(native) {
                    val sameHost=runCatching{
                        java.net.URI(item.url).host.equals(
                            java.net.URI(link).host,ignoreCase=true)
                    }.getOrDefault(false)
                    NativeMediaDownloader.download(link,referer,parent,
                        cookieHeader=if(SiteSession.bound(context,link))
                            SiteSession.cookie(context,link) else null,
                        filenameStem=item.title+" ["+item.id+"]",
                        proxy=NetworkRoute.javaProxy(context,item.url),
                        progress={percent->
                            if(!db.progressClaim(item.id,item.lease,percent))
                                throw java.util.concurrent.CancellationException(
                                    "Native video transfer lease superseded")
                            status(percent)
                        },
                        cancelled={
                            stopRequests.contains(item.id) ||
                            Thread.currentThread().isInterrupted ||
                            !db.ownsClaim(item.id,item.lease)
                        },
                        verifiedMp4=verifiedFirstPartyMp4)
                    0
                } else {
                    // Direct publisher-attested HLS/DASH is NOT an ordinary
                    // webpage. The old Java yt-dlp wrapper drops options and
                    // sometimes fails before writing a single fragment.
                    // Run a selected manifest through the typed embedded
                    // Python API first, with explicit referer, output paths
                    // and concurrent fragments. Preserve the existing legacy
                    // route for all non-manifest pages and no-byte fallback.
                    val manifest=VideoHlsDispatch.isSelectedManifest(link)
                    fun legacy():Int {
                        VideoTransferJournal.record(context,item.id,"original_engine")
                        val exit=LegacyYtDlpTransfer.download(context,link,referer,
                            selected,proxy,parent,template,quality){percent->
                            if(stopRequests.contains(item.id) ||
                                Thread.currentThread().isInterrupted ||
                                !db.ownsClaim(item.id,item.lease))
                                throw java.util.concurrent.CancellationException(
                                    "Transfer cancelled by user")
                            if(!db.progressClaim(item.id,item.lease,percent))
                                throw java.util.concurrent.CancellationException(
                                    "Python transfer lease superseded")
                            status(percent)
                        }
                        VideoTransferJournal.record(context,item.id,"original_complete")
                        return exit
                    }
                    fun typed():Int {
                        // Do not recheck Python after fetching the short-lived
                        // original playlist; source may expire before first byte.
                        if(!signedRuntimePrimed)ensureExtractor(context)
                        if(manifest)VideoTransferJournal.record(
                            context,item.id,"selected_manifest_typed")
                        return EmbeddedYtDlpTransfer.download(
                            link,referer,selected,proxy,parent,template,quality)
                    }
                    try {
                        if(manifest)typed() else legacy()
                    }catch(primary:Exception){
                        if(stopRequests.contains(item.id) ||
                            Thread.currentThread().isInterrupted)
                            throw java.util.concurrent.CancellationException("Video paused")
                        // A partially received original is resumable by the
                        // original downloader on the next attempt. Starting
                        // another extractor on the same .part files can corrupt
                        // or invalidate that resume state.
                        val partial=parent.listFiles()?.any{
                            it.isFile && it.length()>0L &&
                                (it.name.endsWith(".part",true) ||
                                 it.name.endsWith(".ytdl",true) ||
                                 Regex("""(?i)\.part-frag[0-9]{1,10}$""")
                                     .containsMatchIn(it.name))
                        }==true
                        if(partial){
                            VideoTransferJournal.record(context,item.id,
                                "resume_pending",primary.javaClass.simpleName)
                            throw primary
                        }
                        VideoTransferJournal.record(context,item.id,
                            if(manifest)"legacy_manifest_fallback" else "python_fallback",
                            primary.javaClass.simpleName)
                        try{
                            if(manifest)legacy() else typed()
                        }catch(secondary:Exception){
                            secondary.addSuppressed(primary)
                            throw secondary
                        }
                    }
                }
            }
            extractionJobs[item.id]=future
            val start=android.os.SystemClock.elapsedRealtime()
            var latestBytes=VideoPayloadBytes.count(parent)
            var lastByteTick=start
            var lastLeaseTick=0L
            var lastHlsFragmentPercent=0
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
                    val current=VideoPayloadBytes.count(parent)
                    if(VideoTransferLimits.resolvingStalled(
                            now-start,current) ||
                        VideoTransferLimits.preparationBudgetExceeded(
                            now-preparationStart,current>0L))
                        throw java.util.concurrent.TimeoutException(
                            "Selected video yielded no original bytes after "+
                            (VideoTransferLimits.NO_BYTES_RESOLVE_MS/1000)+"s; "+
                            "check the selected player before retrying")
                    if(current>latestBytes){
                        latestBytes=current
                        lastByteTick=now
                        VideoTransferJournal.record(
                            context,item.id,"receiving",current.toString())
                    }
                    // The shipped yt-dlp stores completed fragment counts in
                    // a .ytdl checkpoint even when its Java progress callback
                    // reports 0%. Count only actual complete HLS fragments
                    // after original media bytes exist on disk. Never infer
                    // the rate from a predicted bitrate or sparse file.
                    if(!native && current>0L){
                        val fragments=VideoHlsFragmentProgress.inspect(parent)
                        if(fragments!=null &&
                            fragments.percent>lastHlsFragmentPercent) {
                            lastHlsFragmentPercent=fragments.percent
                            if(!db.progressClaim(item.id,item.lease,
                                    fragments.percent))
                                throw java.util.concurrent.CancellationException(
                                    "HLS fragment progress lease superseded")
                            VideoTransferJournal.record(context,item.id,
                                "fragments",fragments.description)
                            status(fragments.percent)
                        }
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
                // Interrupted yt-dlp operations may never stop immediately.
                // Do not retain cancelled pending FutureTasks forever.
                VideoExecutionPools.discardCancelled(
                    if(native)nativeExecutor else transferExecutor)
                // The embedded player session is only needed while its
                // exact extraction is active; never persist it on disk.
                selectedHostCookies?.delete()
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
            val proxy=NetworkRoute.extractorProxy(context,item.url)
            val started=android.os.SystemClock.elapsedRealtime()
            val future=metadataExecutor.submit<JSONObject>{
                ensureExtractor(context)
                EmbeddedYtDlpMetadata.inspect(link,referer,selected,proxy)
            }
            extractionJobs[item.id]=future
            lateinit var json:JSONObject
            try{
                while(true){
                    if(stopRequests.contains(item.id) ||
                        Thread.currentThread().isInterrupted)
                        throw java.util.concurrent.CancellationException("Video paused")
                    if(!db.touchClaim(item.id,item.lease))
                        throw java.util.concurrent.CancellationException("Lease released")
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(VideoTransferLimits.resolvingStalled(now-started,
                            VideoPayloadBytes.count(parent)) ||
                        VideoTransferLimits.preparationBudgetExceeded(
                            now-preparationStart,
                            VideoPayloadBytes.count(parent)>0L))
                        throw java.util.concurrent.TimeoutException(
                            "Provider metadata resolution took too long without original bytes")
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
                VideoExecutionPools.discardCancelled(metadataExecutor)
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
        fun execute(link:String,referer:String?=null,
                    verifiedFirstPartyMp4:Boolean=false) {
            executeControlled(link,referer,verifiedFirstPartyMp4)
            val received=VideoPayloadBytes.count(parent)
            if(received<=0L)
                throw java.io.IOException("No original media bytes received")
            // Saved=100% is set only after the verified MediaStore receipt.
        }
        // For Eporner use the verified publisher's own embed first. Never
        // silently bypass an age/login requirement or send cookies cross-site.
        // Other providers retain existing canonical-URL behavior.
        // The exact selected publisher parser goes FIRST, including
        // scheduled site sync. The old resolver fetched the same HTML
        // repeatedly through generic discovery and WatchPorn on EVERY site
        // before reaching the relevant provider, stalling parallel slots.
        // Prewarm the existing extractor BEFORE acquiring a short-lived
        // signed Jav Guru/Pornhub HLS URL. Avoid a Python/yt-dlp startup
        // pause between publisher discovery and the first media fragment.
        if(VideoHlsDispatch.needsRuntimeBeforeSource(item.url)){
            VideoTransferJournal.record(context,item.id,"signed_hls_runtime")
            inspectPublisher{ensureExtractor(context)}
            signedRuntimePrimed=true
        }
        val publisherOriginals=if(canInspectPublisherHtml &&
            PublisherOriginalExtractors.owns(item.url) &&
            VideoSiteProfiles.isVideoPage(item.url))
            publisherResult{PublisherOriginalExtractors.resolve(context,item.url)}
        else Result.success(emptyList())
        val publishedOriginals=publisherOriginals.getOrDefault(emptyList())
        // A previous 3DHentaiX job saved a real but UNRELATED playable video.
        // No generic fallback is permitted for this provider. If the selected
        // player does not identify one unambiguous source, fail instead.
        val strictArchivebate=ArchivebateSelectedMedia.isSelectedArticle(item.url)
        // User-reported NSFS-524 failure: after receiving real Roshy bytes,
        // a failed HLS leg retried the *article* via generic Python yt-dlp.
        // That fallback is neither the selected playable original nor safe
        // to mix with an in-progress HLS .part file.
        val strictRoshy=NetworkRoute.siteKey(item.url)=="roshy.tv" &&
            VideoSiteProfiles.isVideoPage(item.url)
        val strictThreeD=NetworkRoute.siteKey(item.url)=="3dhentaix.com"
        val strictRule34=Rule34VideoOriginalSource.videoId(item.url)!=null
        val strictIframe=NetworkRoute.siteKey(item.url) in
            setOf("javideo.net","helloporn.com","uncensoredhentai.xxx")
        val strictSelectedArticle=NetworkRoute.siteKey(item.url) in
            setOf("mp4hentai.com","hentaihaven.xxx") &&
            VideoSiteProfiles.isVideoPage(item.url)
        if(strictRoshy && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Roshy selected original stream was not resolved; "+
                    "never retry its watch article through generic Python"))
        if(strictArchivebate && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Archivebate selected recording exposed no verified Mixdrop "+
                    "player; generic Python page extraction is not an original"))
        if(strictSelectedArticle && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Selected article exposed no attributable original; "+
                    "the HTML page and recommended media are not downloads"))
        if(strictIframe && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Selected publisher iframe exposed no attributable direct media; "+
                    "a player webpage is not a downloadable original"))
        val strictJavGuru=NetworkRoute.siteKey(item.url)=="jav.guru"
        if(strictRule34 && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Rule34Video selected page exposed no verified download quality links; "+
                    "the player page and unrelated previews are not transferable"))
        if(strictJavGuru && publishedOriginals.isEmpty())
            throw (publisherOriginals.exceptionOrNull()?:
                IllegalStateException(
                    "Jav Guru selected article exposed no publisher-attested "+
                    "STREAM controls or valid player relay; connect the "+
                    "site if its normal page requires verification."))
        if(strictThreeD && publishedOriginals.size!=1)
            throw IllegalStateException(
                "3DHentaiX original identity unverified: selected player "+
                "must expose exactly one attributable media source")
        val publishedPlayerInspection=if(!canInspectPublisherHtml ||
            publishedOriginals.isNotEmpty())
            Result.success(emptyList<String>()) else publisherResult {
                WebDiscovery.embeddedCandidates(item.url,context,
                    explicitlySelected=userRequested)
            }
        val discoveryFailure=publishedPlayerInspection.exceptionOrNull()
        var renderedPlayerFailure:Throwable?=null
        val variants=VideoCandidatePlan.ordered(item.url,
            EpornerOriginalSource.videoCandidates(item.url,connected),
            publishedPlayerInspection.getOrDefault(emptyList()))
        val watchPorn=if(!canInspectPublisherHtml ||
            NetworkRoute.siteKey(item.url)!="watchporn.to")null else publisherResult{
            WatchPornOriginalSource.resolve(item.url,context)
        }.getOrNull()
        var last:Throwable?=null
        var downloaded=false
        var expectedDuration:Long?=null
        var heldReason:String?=null
            // Real selected FreePornVideos HTML5MediaEmbed exposes 3 verified
            // HTTPS MP4 formats but its selected file URL has no .mp4 suffix.
            // The old native lane rejected it and retried a Python page
            // extractor despite HTTP 206 + video/mp4 + valid ftyp bytes.
            // Resolve the actual selected original through in-app yt-dlp
            // metadata, then transfer it natively with strict MIME and magic.
            if(!downloaded && userRequested &&
                FreePornVideosOriginalSource.selectedPage(item.url) &&
                !FullLengthVideoPolicy.isExplicitPreview(item.title,item.url)){
                // This is the exact selected scene, matched by the numeric
                // /videos/<id>/ and /get_file/.../<id>_<quality>m.mp4/
                // identifiers. Do not start Python when the publisher has
                // already supplied the attributable first-party original.
                val selectedFormat=publishedOriginals.firstOrNull{
                    FreePornVideosOriginalSource.selectedFileQuality(
                        item.url,it.url)!=null
                }
                if(selectedFormat!=null){
                    try{
                        execute(selectedFormat.url,selectedFormat.referer,
                            verifiedFirstPartyMp4=true)
                        downloaded=true
                    }catch(t:Exception){last=t}
                }
                val format=if(downloaded)Result.success(null) else publisherResult{
                    ensureExtractor(context)
                    val selected=connectedCookies?.takeIf{
                        it.isFile&&it.length()>0L}
                        ?:cookie.takeIf{it.isFile&&it.length()>0L}
                    EmbeddedYtDlpMetadata.firstPartyMp4(
                        item.url,item.url,selected,
                        NetworkRoute.extractorProxy(context,item.url))
                }
                if(format.isSuccess && format.getOrNull()!=null){
                    try {
                        execute(format.getOrThrow()!!,item.url,
                            verifiedFirstPartyMp4=true)
                        downloaded=true
                    }catch(t:Exception){last=t}
                }else if(format.isFailure)last=format.exceptionOrNull()
            }
            // A concrete public media URL does not need Python metadata first.
            if(NativeMediaDownloader.isNativeEligible(item.url,item.url) &&
                !FullLengthVideoPolicy.isExplicitPreview(item.title,item.url) &&
                (userRequested || FullLengthVideoPolicy.knownFullReferenceHost(item.url))){
                try{execute(item.url);downloaded=true}catch(t:Exception){last=t}
            }
            if(!downloaded && watchPorn!=null){
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
            // Direct original links specifically published by the selected
            // site's actual player take precedence over unsupported generic
            // page extractors. A legal page, thumbnail or short teaser is
            // never accepted as a downloaded original.
            if(!downloaded)for(source in publisherOriginals.getOrDefault(emptyList())){
                if(stopRequests.contains(item.id))
                    throw java.util.concurrent.CancellationException("Video paused")
                if(FullLengthVideoPolicy.isExplicitPreview(item.title,source.url))
                    continue
                if(Rule34VideoOriginalSource.firstPartyDownload(
                    item.url,source.url) ||
                    ThreeDHentaiSignedOriginal.firstPartyFormat(
                        item.url,source.url) ||
                    FullPornerOriginalSource.verifiedMedia(
                        item.url,source.referer,source.url)){
                    try {
                        execute(source.url,source.referer,
                            verifiedFirstPartyMp4=true)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(NativeMediaDownloader.isDirectMediaUrl(source.url)){
                    try {
                        execute(source.url,source.referer)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(strictIframe &&
                    source.referer==item.url &&
                    SiteSpecificOriginalSources.selectedExtractorFrame(
                        item.url,source.url)){
                    // Rule34 succeeds by using a publisher-attested original
                    // directly. Here the equivalent publisher-attested
                    // artifact is an exact selected VOE/Streamtape iframe.
                    // It is NOT treated as an MP4: try the actual extractor,
                    // then require media bytes and playable-file validation.
                    // Skipping duplicate metadata avoids two failing probes.
                    try {
                        execute(source.url,source.referer)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(runCatching{
                    java.net.URI(source.url).path.orEmpty().matches(
                        Regex("""(?i)^.*\.(?:m3u8|mpd)$"""))
                }.getOrDefault(false)){
                    // A published HLS/DASH manifest is itself an exact
                    // selected stream. The retained transfer engine handles
                    // fragments, interruption and final local media validation.
                    try{
                        execute(source.url,source.referer)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(Mp4HentaiPublishedHost.isPublishedHost(item.url,source.url)){
                    try {
                        val direct=inspectPublisher{Mp4HentaiPublishedHost.resolve(
                            context,item.url,source.url)}
                        execute(direct,source.url)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(ArchivebateSelectedMedia.isSelectedEmbed(item.url,source.url)){
                    try {
                        execute(inspectPublisher{ArchivebateSelectedMedia.resolve(
                            context,item.url,source.url)},source.url)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else if(strictJavGuru && userRequested){
                    // The shortcode and both first-party relay steps
                    // already prove the selected hoster identity. Start its
                    // original transfer without repeating generic JavGuru
                    // Python metadata extraction and its 50s timeout.
                    try {
                        execute(source.url,source.referer)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else{
                    val checked=runCatching{preflight(source.url,source.referer)}
                    if(checked.isSuccess &&
                        checked.getOrThrow().second.verdict==
                            FullLengthVideoPolicy.Verdict.ACCEPT){
                        try {
                            execute(source.url,source.referer)
                            expectedDuration=checked.getOrThrow().first
                            downloaded=true
                            break
                        }catch(t:Exception){last=t}
                    }else if(checked.isFailure){
                        last=checked.exceptionOrNull()
                    }else heldReason=checked.getOrThrow().second.reason
                }
            }
            // A published external file-host landing page is not playable
            // media and must never be downloaded as a successful MP4. Do not
            // silently retry the MP4Hentai article through generic yt-dlp.
            if(!downloaded && publishedOriginals.any{
                    Mp4HentaiPublishedHost.isPublishedHost(item.url,it.url)})
                throw (last?:IllegalStateException(
                    "MP4Hentai selected file hosts exposed no verified media URL"))
            if(strictRoshy && !downloaded)
                throw (last?:IllegalStateException(
                    "Roshy exact selected player did not complete; "+
                    "retain HLS fragments for resume instead of attempting "+
                    "unrelated Python watch-page extraction"))
            if(strictArchivebate && !downloaded)
                throw (last?:IllegalStateException(
                    "Archivebate selected Mixdrop player did not expose a "+
                    "transferable original; cannot substitute a generic "+
                    "Python extractor, preview, or unrelated scene"))
            if(strictSelectedArticle && !downloaded)
                throw (last?:IllegalStateException(
                    "Selected article file host or player exposed no transferable original"))
            if(strictIframe && !downloaded)
                throw (last?:IllegalStateException(
                    "Selected external player did not transfer the original media"))
            if(strictRule34 && !downloaded)
                throw (last?:IllegalStateException(
                    "Rule34Video published original failed the verified first-party transfer"))
            if(strictThreeD && !downloaded)
                throw (last?:IllegalStateException(
                    "3DHentaiX selected original failed; unrelated media "+
                    "and generic extractor fallback are prohibited"))
            if(strictJavGuru && !downloaded)
                throw (last?:IllegalStateException(
                    "Jav Guru selected STREAM players failed. "+
                    "The publisher page and unrelated ads are not download candidates."))
            val publisherReferers=publishedOriginals.associate{it.url to it.referer}
            if(!downloaded) for(candidate in (publisherReferers.keys+variants)){
                if(VideoTransferLimits.preparationBudgetExceeded(
                        android.os.SystemClock.elapsedRealtime()-preparationStart,
                        VideoPayloadBytes.count(parent)>0L))
                    throw java.util.concurrent.TimeoutException(
                        "Selected publisher exhausted its total original "+
                        "resolution budget")
                if(stopRequests.contains(item.id))
                    throw java.util.concurrent.CancellationException("Video paused")
                if(ArchivebateSelectedMedia.isSelectedEmbed(item.url,candidate)){
                    // This selected /watch/<id> points to an actual Mixdrop
                    // player. yt-dlp reports Unsupported URL for its /e/<id>.
                    // Read the exact selected HTML5 player instead; never
                    // take recommendation MP4s from the parent watch page.
                    if(!userRequested){
                        heldReason="Select the exact Archivebate recording before downloading"
                        continue
                    }
                    try{
                        val media=inspectPublisher{ArchivebateSelectedMedia.resolve(
                            context,item.url,candidate)}
                        execute(media,candidate)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                    continue
                }
                // The publisher's selected watch page is the required HTTP
                // Referer for third-party player and CDN hosts. Losing it
                // caused HTTP 403 even after a valid player URL was found.
                val candidateReferer=publisherReferers[candidate]
                    ?:VideoCandidatePlan.referer(item.url,candidate)
                // Resolve genuine first-party HTML5 <video> and contentUrl
                // entries BEFORE running generic yt-dlp metadata/download.
                // This works for any publisher with a real media element,
                // without adding site-specific fake player links.
                val directlyPublished=if(canInspectPublisherHtml)
                    publisherResult{WebDiscovery.directMedia(candidate,context)}
                        .onFailure{cause->
                            // A real browser may show ONLY preview clips or a
                            // verification interstitial. Prefer that diagnosis
                            // over yt-dlp's generic Unsupported URL when no
                            // full original is subsequently resolved.
                            if(renderedPlayerFailure==null)
                                renderedPlayerFailure=cause
                        }.getOrNull() else null
                if(userRequested &&
                    !directlyPublished.isNullOrBlank() &&
                    directlyPublished!=candidate &&
                    NativeMediaDownloader.isNativeEligible(item.url,directlyPublished) &&
                    !FullLengthVideoPolicy.isExplicitPreview(
                        item.title,directlyPublished)){
                    try{
                        execute(directlyPublished,candidate)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }
                val trusted=FullLengthVideoPolicy.knownFullReferenceHost(candidate)
                val decision=if(trusted)FullLengthVideoPolicy.assess(
                    item.title,item.title,candidate,null,null,
                    userExplicitlyRequested=userRequested) else null
                if(decision!=null &&
                    decision.verdict!=FullLengthVideoPolicy.Verdict.ACCEPT){
                    heldReason=decision.reason
                    continue
                }
                if(trusted){
                    try{
                        execute(candidate,candidateReferer)
                        downloaded=true
                        break
                    }catch(t:Exception){last=t}
                }else{
                    val checked=runCatching{preflight(candidate,candidateReferer)}
                    if(checked.isSuccess){
                        val verified=checked.getOrThrow().second
                        if(verified.verdict==FullLengthVideoPolicy.Verdict.ACCEPT){
                            try{
                                execute(candidate,candidateReferer)
                                expectedDuration=checked.getOrThrow().first
                                downloaded=true
                                break
                            }catch(t:Exception){last=t}
                        }else heldReason=verified.reason
                    }else{
                        last=checked.exceptionOrNull()
                        // A metadata parser can reject an otherwise playable
                        // page. Explicitly selected downloads get a real transfer
                        // attempt through the original engine, with final byte,
                        // container and MediaStore verification unchanged.
                        // Automatic discovery still requires its existing policy.
                        if(userRequested &&
                            !VideoTransferLimits.preparationBudgetExceeded(
                                android.os.SystemClock.elapsedRealtime()-preparationStart,
                                VideoPayloadBytes.count(parent)>0L) &&
                            !FullLengthVideoPolicy.isExplicitPreview(item.title,candidate)){
                            try{
                                execute(candidate,candidateReferer)
                                downloaded=true
                                break
                            }catch(t:Exception){last=t}
                        }
                    }
                }
                val media=directlyPublished
                if(!media.isNullOrBlank() && media!=candidate){
                    if(trusted && NativeMediaDownloader.isNativeEligible(candidate,media)){
                        try{
                            execute(media,candidate)
                            downloaded=true
                            break
                        }catch(t:Exception){last=t}
                    }else{
                        val checked=runCatching{preflight(media,candidate)}
                        if(checked.isSuccess){
                            val verified=checked.getOrThrow().second
                            if(verified.verdict==FullLengthVideoPolicy.Verdict.ACCEPT){
                                try{
                                    execute(media,candidate)
                                    expectedDuration=checked.getOrThrow().first
                                    downloaded=true
                                    break
                                }catch(t:Exception){last=t}
                            }else heldReason=verified.reason
                        }else{
                            last=checked.exceptionOrNull()
                            if(userRequested &&
                                !VideoTransferLimits.preparationBudgetExceeded(
                                    android.os.SystemClock.elapsedRealtime()-preparationStart,
                                    VideoPayloadBytes.count(parent)>0L) &&
                                !FullLengthVideoPolicy.isExplicitPreview(item.title,media)){
                                try{
                                    execute(media,candidate)
                                    downloaded=true
                                    break
                                }catch(t:Exception){last=t}
                            }
                        }
                    }
                }
            }
        if(stopRequests.contains(item.id))
            throw java.util.concurrent.CancellationException("Video paused")
        if(!downloaded){
            if(heldReason!=null)throw FullVersionUnavailable(heldReason!!)
            val attempted=last
            val category=attempted?.let{
                VideoFailurePolicy.classify(VideoErrorDetail.summary(it))
            }
            val access=listOfNotNull(publisherOriginals.exceptionOrNull(),
                renderedPlayerFailure,discoveryFailure)
                .firstOrNull{failure->
                    val message=failure.message.orEmpty()
                    // Preserve real HTTP 404/410 and rate limits from the
                    // SELECTED publisher page. Previously a later generic
                    // yt-dlp No video formats obscured the actual source 404.
                    message.contains("short preview clips",true) ||
                        VideoFailurePolicy.classify(message) in setOf(
                            VideoFailurePolicy.Kind.AUTH,
                            VideoFailurePolicy.Kind.GONE,
                            VideoFailurePolicy.Kind.RATE_LIMIT)
                }
            // Never hide a genuine server 403/429 or Python runtime crash.
            // Only replace an unrecognized-URL extractor error with concrete,
            // independently observed public-player access evidence.
            if(access!=null && (attempted==null ||
                    category==VideoFailurePolicy.Kind.UNSUPPORTED ||
                    category==VideoFailurePolicy.Kind.METADATA))
                throw access
            throw (attempted?:access?:
                IllegalStateException("No publisher original or supported player found"))
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
                val track=if(audioOnly)MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO
                    else MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
                require(retriever.extractMetadata(track)=="yes"){
                    "Downloaded container has no playable "+(if(audioOnly)"audio" else "video")+" track"
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
            }finally{retriever.release()}
        }.getOrElse { failure ->
            file.delete()
            throw java.io.IOException("Downloaded container could not be played",failure)
        }
        val minimum=expectedDuration?.minus(5L)?.coerceAtLeast(0L)?.times(1000L)?:0L
        if(actual==null || actual<=0L || actual<minimum){
            file.delete()
            throw java.io.IOException("Downloaded video is incomplete or has invalid playback duration")
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
            val published=context.contentResolver.update(uri,
                ContentValues().apply{put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            check(published==1){
                "Video bytes were received but Android Downloads publication failed"
            }
            parent.listFiles()?.forEach { it.delete() }
            parent.delete()
            return uri.toString()
        } catch(t:Exception) {
            context.contentResolver.delete(uri,null,null)
            throw t
        }
        } finally {
            connectedCookies?.delete()
        }
        } finally {
            runCatching { AsterionCwd.enter(context.filesDir) }
        }
    }
}

class TransferService: Service() {
    @Volatile private var active:java.util.concurrent.Future<*>?=null
    @Volatile private var timedOut=false
    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(timedOut){stopSelf();return START_NOT_STICKY}
        try {
            VideoEngine.makeChannel(this)
            startForeground(24014,VideoEngine.notification(this,"Preparing downloads"))
        }catch(t:Exception){
            // Keep pending originals in the durable database; JobScheduler may
            // continue work when Android disallows foreground promotion.
            runCatching{Scheduling.requestPrimaryImmediate(applicationContext,
                intent?.getBooleanExtra("scan",false)==true,
                intent?.getStringExtra("target_site_host"))}
            stopSelf()
            return START_NOT_STICKY
        }
        // A second tap while work is active should not spawn a duplicate
        // lifecycle waiter. The existing VideoDb claims are shared across
        // isolated processes and work() drains pending user selections.
        if(active?.isDone==false)return START_NOT_STICKY
        val future=VideoEngine.submit(applicationContext,
            intent?.getBooleanExtra("scan",false)==true,
            intent?.getStringExtra("target_site_host")) { message ->
            if(!timedOut)runCatching{
                getSystemService(NotificationManager::class.java)
                    .notify(24014,VideoEngine.notification(this,message.take(80)))
            }
        }
        active=future
        Thread({
            try { future.get() }
            catch(_:Exception){ /* Recorded by VideoEngine, persisted in VideoDb. */ }
            finally {
                synchronized(this){
                    // Old lifecycle waiters must never stop a newer active
                    // transfer started by another manual selection.
                    if(active===future){
                        active=null
                        // stopSelfResult(old startId) can leave FGS
                        // indefinitely after a newer start; this guarded
                        // stopSelf owns only the current active generation.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        },"videoflow-primary-lifecycle").start()
        // Never replay a crashed service intent in an Android restart loop.
        return START_NOT_STICKY
    }

    /** Android 15+ enforces a finite quota for dataSync foreground services.
     * Stop immediately on expiry and preserve the queue for a JobScheduler
     * retry after stale in-flight leases recover; do not discard originals.
     */
    override fun onTimeout(startId:Int,fgsType:Int) {
        timedOut=true
        active?.cancel(true)
        active=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        runCatching{Scheduling.requestRetry(applicationContext,4L*60_000L)}
    }
    override fun onDestroy() {
        active?.cancel(true)
        active=null
        super.onDestroy()
    }
}
