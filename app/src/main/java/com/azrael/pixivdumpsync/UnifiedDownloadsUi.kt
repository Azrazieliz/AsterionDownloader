package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.DownloadManager
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.*
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.VideoRuntimeState
import com.asterion.videoflow.VideoTransferJournal
import java.util.concurrent.Executors

/** The only general-purpose download queue UI. Each row is a real file/job,
 * not a provider shortcut or a cloud-handoff indicator.
 */
object UnifiedDownloadsUi {
    private val ui=NocturneDesign
    private val thumbnails=Executors.newFixedThreadPool(2)
    // Recovery cooldowns are per download module. A newly idle EPUB queue
    // must not wait three minutes merely because Video was rearmed first.
    private val lastQueueRecovery=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private const val RECOVERY_INTERVAL_MS=25_000L
    private const val RECONCILE_REFRESH_MS=20_000L
    private const val EVENT_COALESCE_MS=240L
    private fun rowId(row:Row):String =
        row.key ?: (row.source+"|"+(row.saved ?: row.filename))
    private fun hiddenPrefs(a:Activity)=a.getSharedPreferences(
        "asterion_downloads_hidden_v1",0)
    private fun hideRow(a:Activity,row:Row) {
        val prefs=hiddenPrefs(a)
        val old=prefs.getStringSet("keys",emptySet()).orEmpty()
        prefs.edit().putStringSet("keys",(old+rowId(row)).toSet()).apply()
    }
    /** Auto-rearm ONLY due, persisted queues. Reading the Downloads screen
     * cannot rescan the internet, reset a retry cooldown or restart downloads
     * on every 3-second UI refresh. Work is already persisted in each DB.
     */
    private fun recoverIdleQueues(a:Activity,rows:List<Row>) {
        if(!AppPower.enabled(a))return
        // An Android low-memory/service kill can leave a leased video shown
        // as "Connecting to source" after its process is gone. The normal
        // queue recovery below excludes a module with an ACTIVE row and
        // therefore never wakes the missing worker. Inspect only expired
        // leases, on a bounded background cadence, and rearm the same engine.
        // VideoDb.recover() checks live heartbeat_at atomically and leaves
        // saved files, paused jobs and resumable .part bytes untouched.
        if(rows.any{it.source=="Video" && it.state==State.ACTIVE}){
            val now=android.os.SystemClock.elapsedRealtime()
            val permitted=synchronized(lastQueueRecovery){
                val prior=lastQueueRecovery["Video:stale"]?:0L
                if(prior>0L && now-prior<RECOVERY_INTERVAL_MS)false
                else{
                    lastQueueRecovery["Video:stale"]=now
                    true
                }
            }
            if(permitted){
                val app=a.applicationContext
                kotlin.concurrent.thread(name="asterion-video-stale-lease-recovery"){
                    val reclaimed=runCatching{
                        VideoDb(app).use{it.recover()}
                    }.getOrDefault(0)
                    if(reclaimed>0){
                        DownloadStateSignals.changed(app)
                        runCatching{VideoEngine.launch(app,false)}
                    }
                }
            }
        }
        // A VIDEO extractor may be resolving for minutes while X, comics or
        // books have queued work. Never let that unrelated active row starve
        // their persisted queues. Equally, never start a second worker for a
        // module already running a job (especially a native torrent worker).
        val queued=DownloadQueueRecoveryPolicy.eligibleSources(
            rows.filter{it.state==State.QUEUED}.map{it.source}.toSet(),
            rows.filter{it.state==State.ACTIVE}.map{it.source}.toSet())
        if(queued.isEmpty())return
        val now=android.os.SystemClock.elapsedRealtime()
        val due=synchronized(lastQueueRecovery) {
            queued.filter { source->
                val previous=lastQueueRecovery[source]?:0L
                if(previous>0L && now-previous<RECOVERY_INTERVAL_MS)false
                else {
                    lastQueueRecovery[source]=now
                    true
                }
            }.toSet()
        }
        if(due.isEmpty())return
        kotlin.concurrent.thread(name="asterion-queued-worker-rearm"){
            val app=a.applicationContext
            if("Video" in due){
                runCatching{VideoEngine.launch(app,false)}
            }
            if("X" in due)runCatching{XPhotoEngine.start(app)}
            if("Comic ZIP" in due)runCatching{ChapterArchives.start(app)}
            if("EPUB" in due || "Novel ZIP" in due)
                runCatching{NovelEpubService.schedule(app)}
            if(due.any{it !in setOf("Video","X","Comic ZIP","EPUB","Novel ZIP")})
                runCatching{DirectDownloadRetry.ensure(app)}
        }
    }
    private enum class State { ACTIVE, QUEUED, PAUSED, HELD, FAILED, DONE }
    private enum class Filter(val label:String) {
        ALL("Unfinished"),ACTIVE("Transferring"),QUEUED("Queued"),
        PAUSED("Paused"),HELD("Needs action"),FAILED("Failed"),DONE("Saved")
    }
    /** View-only filters; they never mutate the underlying transfer queue. */
    private fun selectedFilter(
        prefs:android.content.SharedPreferences,source:String
    ):Filter=Filter.entries.firstOrNull{
        it.name==prefs.getString("filter:"+source,"ALL")
    }?:Filter.ALL
    private fun matchesFilter(state:State,filter:Filter):Boolean =
        if(filter==Filter.ALL)state!=State.DONE else state.name==filter.name
    private fun clearFailedJob(a:Activity,job:Row){
        if(job.source=="Video"){
            job.key?.removePrefix("video:")?.toLongOrNull()?.let{id->
                VideoDb(a).use{db->
                    if(!db.clearTerminalFailure(id))
                        db.acknowledgeMissingSaved(id)
                }
            }
        }else{
            // Use existing durable dismissals to remove real failure totals
            // instead of concealing an item while leaving the alert inflated.
            job.onRemove?.invoke() ?: hideRow(a,job)
        }
        DownloadStateSignals.changed(a)
    }
    private data class Row(
        val source:String,val filename:String,val info:String,val state:State,
        val percent:Int?,val updated:Long,val action:()->Unit,
        val saved:String?=null,val image:String?=null,val mime:String="*/*",
        val actionText:String?=null,
        val receivedBytes:Long=0L,
        val onRemove:(()->Unit)?=null,
        val hasPrimaryAction:Boolean=true,
        val key:String?=null,
        val transferStage:String?=null,
        val transferUpdated:Long=0L,
        val totalBytes:Long=-1L,
        val posterPage:String?=null,
        val reportedProgress:Int?=null
    )
    private fun open(a:Activity,uri:String,mime:String){
        runCatching{
            a.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri),mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }.onFailure{
            Toast.makeText(a,"No compatible viewer is installed",Toast.LENGTH_LONG).show()
        }
    }
    /** A database 'done' field alone does not prove the local media still exists. */
    private fun readable(a:Activity,value:String?):Boolean{
        if(value.isNullOrBlank())return false
        return runCatching{
            val uri=Uri.parse(value)
            when(uri.scheme){
                "content"->a.contentResolver.openFileDescriptor(uri,"r")?.use {
                    it.statSize>0L
                }?:false
                "file"->java.io.File(uri.path.orEmpty()).let{it.isFile&&it.length()>0L}
                else->false
            }
        }.getOrDefault(false)
    }
    private fun fileName(a:Activity,value:String?,fallback:String):String{
        if(value.isNullOrBlank())return fallback.substringAfterLast('/').take(130)
        val uri=Uri.parse(value)
        val name=runCatching{
            a.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),
                null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else null}
        }.getOrNull()
        return name?.takeIf{it.isNotBlank()}?.take(130)
            ?:fallback.substringAfterLast('/').substringBefore('?').take(130)
    }
    private fun collect(a:Activity):List<Row>{
        val rows=ArrayList<Row>()
        VideoRuntimeState.failure(a)?.let{failure->
            rows+=Row("Video engine","Video engine unavailable",
                "Embedded Python startup failed · "+failure.take(160),
                State.FAILED,null,System.currentTimeMillis(),{
                    Toast.makeText(a,"Runtime diagnostics: "+failure.take(180),
                        Toast.LENGTH_LONG).show()
                })
        }
        VideoDb(a).use{db->
            // Always load each durable state separately: the old first-180
            // global query sorted queued before saved/failed/retrying, so a
            // transfer vanished as soon as it left the active state.
            // Bounded status slices keep the gallery responsive with 1,000+
            // records while never hiding every error or completed receipt.
            val tracked=LinkedHashMap<Long,com.asterion.videoflow.Video>()
            for((tab,count) in listOf(
                "Active" to 25, "Failed" to 70, "Held" to 70,
                "Saved" to 50, "Paused" to 30, "Queued" to 100)){
                for(video in db.listVideosForTab(tab,count))
                    tracked.putIfAbsent(video.id,video)
            }
            for(item in tracked.values){
                // The owner has explicitly deleted these providers. Retain
                // previously saved bytes and diagnostic history, but do not
                // recreate their failed, paused or saved cards in Downloads.
                if(ProviderRetirement.isRetired(item.url))continue
                val present=readable(a,item.savedUri)
                // A failed HLS extraction must not make received original
                // bytes disappear from the UI. The durable stage journal
                // survives both retry_wait and user pause.
                val journal=if(item.status in setOf(
                    "running","paused","retry_wait","error","full_only_hold"))
                    VideoTransferJournal.current(a,item.id) else null
                val bytes=journal?.verifiedBytes?.coerceAtLeast(0L)?:0L
                val received=if(bytes>0)
                    String.format(java.util.Locale.US,"%.1f MiB",
                        bytes.toDouble()/1_048_576.0) else ""
                val hlsFragments=journal?.takeIf{it.stage=="fragments"}
                    ?.detail?.takeIf{
                        Regex("""[1-9][0-9]{0,6}/[1-9][0-9]{0,6} fragments""")
                            .matches(it)
                    }
                val hlsCompletion=DownloadOverviewPolicy.hlsCompletion(
                    hlsFragments)
                val state=when{
                    item.status=="done" && present->State.DONE
                    item.status=="done" || item.status=="error"->State.FAILED
                    item.status in setOf("full_only_hold","retry_wait")->State.HELD
                    item.status=="paused"->State.PAUSED
                    item.status=="running"->State.ACTIVE
                    else->State.QUEUED
                }
                val retired=ProviderRetirement.isRetired(item.url)
                val verifySelectedHoster=
                    com.asterion.videoflow.NetworkRoute.siteKey(item.url)=="mp4hentai.com" &&
                    item.error.orEmpty().let{reason->
                        reason.contains("CAPTCHA",ignoreCase=true) ||
                        reason.contains("host did not expose a verified download",
                            ignoreCase=true) ||
                        reason.contains("interactive verification",
                            ignoreCase=true) ||
                        reason.contains("file hoster",ignoreCase=true)
                    }
                val info=when(state){
                    State.DONE->"Video · Saved on device"
                    State.FAILED->"Video · "+(item.error?:"Saved file is missing")
                    State.HELD->{
                        val reason=if(item.status=="retry_wait")
                            "Retry scheduled: "+item.error.orEmpty().take(95)
                        else item.error.orEmpty().ifBlank{
                            "Previous source verification inconclusive; retry available"
                        }.take(95)
                        "Video · "+reason+
                            if(bytes>0L)" · $received retained" else ""
                    }
                    State.PAUSED->if(retired)"Video · Source retired"
                        else "Video · Paused by user; "+
                            if(bytes>0L)"$received original retained"
                            else "progress retained"
                    State.QUEUED->"Video · Waiting for transfer worker"
                    State.ACTIVE->"Video · "+when {
                        bytes>0L && hlsFragments!=null->
                            "Receiving original · "+received+" · "+hlsFragments
                        bytes>0L && item.progress>0->
                            "Receiving original · "+received+" · total size unknown"
                        bytes>0L->"Receiving original · "+received+
                            " · measuring HLS completion"
                        item.progress>0->"Engine reports "+item.progress+
                            "% · checking written bytes"
                        journal?.stage=="transfer"->"Connecting to original"
                        journal?.stage=="python_fallback"->"Switching extractor"
                        else->"Inspecting / preparing original"
                    }
                }
                rows+=Row("Video",fileName(a,if(present)item.savedUri else null,item.title),
                    info,state,if(state==State.ACTIVE)hlsCompletion else null,item.updated,{
                        if(!retired || state==State.DONE)when(state){
                            State.DONE->item.savedUri?.let{open(a,it,"video/*")}
                            State.FAILED,State.HELD->{
                                if(item.status=="done" && !present){
                                    // The old SQLite DONE flag survives a
                                    // deleted/moved MediaStore file. The normal
                                    // retry only accepts error rows, so this
                                    // must explicitly requeue a missing receipt.
                                    val accepted=VideoDb(a).use{
                                        it.retryMissingSaved(item.id)
                                    }
                                    if(accepted)VideoEngine.launch(a,false)
                                    else Toast.makeText(a,
                                        "Saved file changed; recheck before retry",
                                        Toast.LENGTH_LONG).show()
                                }else if(verifySelectedHoster){
                                    // Reopen the exact selected publisher post.
                                    // The owner follows the publisher's Mirror
                                    // into the genuine file host and completes
                                    // its own interactive verification; no
                                    // challenge is bypassed or wrong file used.
                                    a.startActivity(Intent(a,
                                        com.azrael.galleryflow.SiteLoginActivity::class.java)
                                        .putExtra(
                                            com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,
                                            item.url)
                                        .putExtra(
                                            com.azrael.galleryflow.SiteLoginActivity.EXTRA_RETRY_VIDEO_ID,
                                            item.id))
                                }else{
                                    VideoDb(a).use{it.retry(item.id)}
                                    VideoEngine.launch(a,false)
                                }
                            }
                            State.ACTIVE,State.QUEUED->VideoEngine.pauseVideo(a,item.id)
                            State.PAUSED->VideoEngine.resumeVideo(a,item.id)
                        }
                    },if(present)item.savedUri else null,mime="video/*",
                    receivedBytes=bytes,
                    actionText=when(state){
                        State.ACTIVE->"PAUSE"
                        State.QUEUED->"HOLD"
                        State.PAUSED->"RESUME"
                        State.HELD->if(verifySelectedHoster)"VERIFY FILE HOST" else "RETRY"
                        State.FAILED->if(verifySelectedHoster)"VERIFY FILE HOST" else "RETRY"
                        else->null
                    },
                    onRemove=if(state==State.DONE) ({
                        android.app.AlertDialog.Builder(a)
                            .setTitle("Saved video")
                            .setItems(arrayOf(
                                "Remove from download list (keep file)",
                                "Report wrong original (retain evidence)")){_,choice->
                                if(choice==0)hideRow(a,Row(
                                    "Video",item.title,"",State.DONE,null,0L,{},key=
                                    "video:"+item.id))
                                else VideoDb(a).use{db->
                                    if(db.flagWrongOriginal(item.id))
                                        VideoTransferJournal.record(a,item.id,
                                            "wrong_media",
                                            "Owner reported incorrect selected video")
                                }
                            }.show()
                    }) else ({
                        if(item.status=="done" && !present)
                            VideoDb(a).use{it.acknowledgeMissingSaved(item.id)}
                        else VideoEngine.cancelVideo(a.applicationContext,item.id)
                    }),hasPrimaryAction=!retired || state==State.DONE,
                    key="video:"+item.id,
                    transferStage=journal?.stage,
                    transferUpdated=journal?.updated?:0L,
                    posterPage=item.url,
                    reportedProgress=if(state==State.ACTIVE)
                        item.progress.coerceIn(0,100) else null)
            }
        }
        ChapterQueue(a).use{db->
            for(item in db.list().take(180)){
                val present=readable(a,item.savedUri)
                val state=when{
                    item.state=="done" && present->State.DONE
                    item.state=="done" || item.state=="error"->State.FAILED
                    item.state=="paused"->State.PAUSED
                    item.state=="working"->State.ACTIVE
                    else->State.QUEUED
                }
                rows+=Row("Comic ZIP",fileName(a,if(present)item.savedUri else null,
                    item.title+".zip"),"Comic ZIP · "+when(state){
                        State.DONE->"Saved on device"
                        State.FAILED->item.error?:"Saved archive is missing"
                        State.PAUSED->"Archive paused; cached chapter pages retained"
                        State.ACTIVE->"Downloading ordered chapter images"
                        else->"Waiting for chapter downloader"
                    },state,if(item.state=="working")item.progress else null,item.updated,{
                    when(state){
                        State.DONE->item.savedUri?.let{open(a,it,"application/zip")}
                        State.FAILED->{ChapterQueue(a).use{it.retry(item.id)};ChapterArchives.start(a)}
                        State.ACTIVE,State.QUEUED->ChapterArchives.pause(a,item.id)
                        State.PAUSED->ChapterArchives.resume(a,item.id)
                        else->{}
                    }
                },if(present)item.savedUri else null,mime="application/zip",
                    actionText=when(state){
                        State.ACTIVE->"PAUSE"
                        State.QUEUED->"HOLD"
                        State.PAUSED->"RESUME"
                        State.FAILED->"RETRY"
                        else->null
                    },
                    onRemove={
                        if(state==State.DONE)hideRow(a,Row(
                            "Comic ZIP",item.title,"",state,null,0L,{},
                            key="comic:"+item.id))
                        else{
                            if(state==State.ACTIVE)ChapterArchives.pause(a,item.id)
                            ChapterQueue(a).use{it.dismiss(item.id)}
                        }
                    },key="comic:"+item.id)
            }
        }
        NovelEpubQueue(a).use{db->
            for(item in db.list(120)){
                val zip=NovelEpubParser.readableZipSeries(item.url)
                val mime=if(zip)"application/zip" else "application/epub+zip"
                val present=readable(a,item.savedUri)
                val state=when{
                    item.state=="DONE" && present->State.DONE
                    item.state=="WAIT_LOGIN"->State.HELD
                    item.state=="DONE" && !present->State.FAILED
                    item.state=="ERROR"->State.FAILED
                    item.state=="RETRY" && (item.error.isNullOrBlank() ||
                        item.error.startsWith("Chapter extraction continuing:"))->
                        State.QUEUED
                    item.state=="RETRY"->State.HELD
                    item.state=="PAUSED"->State.PAUSED
                    item.state=="WORKING"->State.ACTIVE
                    else->State.QUEUED
                }
                val type=if(zip)"Novel ZIP" else "EPUB"
                val name=fileName(a,if(present)item.savedUri else null,
                    item.title+if(zip)".zip" else ".epub")
                val cached=NovelEpubService.cachedCount(a,item.url)
                val providerCooling=item.state=="RETRY" &&
                    item.error.orEmpty().contains("HTTP 429") &&
                    item.retryAfter>System.currentTimeMillis()
                rows+=Row(type,name,
                    type+" · "+when(state){
                        State.DONE->"Saved on device"
                        State.FAILED->item.error?:"Saved archive is missing"
                        State.HELD->if(item.state=="WAIT_LOGIN")
                            "Account sign-in required"
                            else if(providerCooling)
                                "Publisher rate limit · automatically resumes at "+
                                    java.text.DateFormat.getDateTimeInstance()
                                        .format(java.util.Date(item.retryAfter))+
                                    " · $cached chapters safely cached"
                            else "Automatic retry scheduled · "+item.error.orEmpty().take(90)
                        State.ACTIVE->"$cached chapters cached · Extracting"
                        State.PAUSED->"$cached chapters cached · Paused"
                        State.QUEUED->if(item.state=="RETRY")
                            "$cached chapters cached · next chapter retrieval scheduled"
                            else "$cached chapters cached · Waiting for original"
                        else->"$cached chapters cached · Continuing"
                    },state,null,item.updated,{
                    when(state){
                        State.DONE->item.savedUri?.let{open(a,it,mime)}
                        State.HELD->if(item.state=="WAIT_LOGIN"){
                            a.startActivity(Intent(a,
                                com.azrael.galleryflow.SiteLoginActivity::class.java)
                                .putExtra(com.azrael.galleryflow.SiteLoginActivity.EXTRA_URL,item.url))
                        }else if(providerCooling){
                            android.widget.Toast.makeText(a,
                                "The novel site requested a cooldown. Saved chapters "+
                                    "will resume automatically afterward.",
                                android.widget.Toast.LENGTH_LONG).show()
                        }else{
                            NovelEpubQueue(a).use{it.retryNow(item.url)}
                            NovelEpubService.schedule(a)
                        }
                        State.FAILED->{
                            NovelEpubQueue(a).use{it.retryNow(item.url)}
                            NovelEpubService.schedule(a)
                        }
                        State.ACTIVE,State.QUEUED->NovelEpubQueue(a).use{
                            it.pause(item.url)
                        }
                        State.PAUSED->{
                            NovelEpubQueue(a).use{it.resumePaused(item.url)}
                            NovelEpubService.schedule(a)
                        }
                        else->{}
                    }
                },if(present)item.savedUri else null,mime=mime,
                    actionText=when{
                        state==State.ACTIVE->"PAUSE"
                        state==State.QUEUED->"HOLD"
                        state==State.PAUSED->"RESUME"
                        providerCooling->"COOLDOWN"
                        state==State.HELD && item.state=="WAIT_LOGIN"->"CONNECT ACCOUNT"
                        state==State.HELD->"RETRY"
                        state==State.FAILED->"RETRY"
                        else->null
                    },
                    onRemove={
                        if(state==State.DONE)hideRow(a,Row(type,name,"",state,
                            null,0L,{},key="novel:"+item.url))
                        else NovelEpubQueue(a).use{it.cancel(item.url)}
                    },key="novel:"+item.url)
            }
        }
        XPhotoQueue(a).use{db->
            for(item in db.recent(180)){
                val present=readable(a,item.saved)
                val state=when{
                    item.state=="done" && present->State.DONE
                    item.state=="done" || item.state=="error"->State.FAILED
                    item.state=="paused"->State.PAUSED
                    item.state=="running"->State.ACTIVE
                    else->State.QUEUED
                }
                val name=fileName(a,if(present)item.saved else null,
                    item.photo.substringBefore('?').substringAfterLast('/').ifBlank{"X image"})
                rows+=Row("X",name,"X image · "+when(state){
                    State.DONE->"Saved on device"
                    State.FAILED->item.error?:"Saved image is missing"
                    State.PAUSED->"Photo paused by user"
                    State.ACTIVE->"Transferring X image"
                    else->"Waiting for photo worker"
                },state,null,item.updated,{
                    when(state){
                        State.DONE->item.saved?.let{open(a,it,"image/*")}
                        State.FAILED->{XPhotoQueue(a).use{it.retry(item.id)};XPhotoEngine.start(a)}
                        State.ACTIVE,State.QUEUED->XPhotoEngine.pause(a,item.id)
                        State.PAUSED->XPhotoEngine.resume(a,item.id)
                        else->{}
                    }
                },if(present)item.saved else null,image=item.photo,mime="image/*",
                    actionText=when(state){
                        State.ACTIVE->"PAUSE"
                        State.QUEUED->"HOLD"
                        State.PAUSED->"RESUME"
                        State.FAILED->"RETRY"
                        else->null
                    },
                    onRemove={
                        if(state==State.DONE)hideRow(a,Row(
                            "X",name,"",state,null,0L,{},key="x:"+item.id))
                        else{
                            if(state==State.ACTIVE)XPhotoEngine.pause(a,item.id)
                            XPhotoQueue(a).use{it.dismiss(item.id)}
                        }
                    },key="x:"+item.id)
            }
        }
        // The Gallery counts come from its durable media table. Previously
        // this Downloads screen loaded only SAVED Gallery thumbnails from
        // MediaStore, so a genuine 3-active/1-queued module always said
        // "No unfinished jobs in the recent queue sample". Show actual
        // nonterminal records without changing their state or canceling sync.
        runCatching {
            com.azrael.galleryflow.GalleryDb(a.applicationContext).use { store ->
                store.readableDatabase.query("media",
                    arrayOf("source","media_id","filename","url","state",
                        "updated_at"),
                    "state IN ('downloading','pending','retrying',"+
                        "'permanent_error','inaccessible')",
                    null,null,null,"updated_at DESC","80").use { cursor ->
                    while(cursor.moveToNext()){
                        val publisher=cursor.getString(0).orEmpty()
                        val mediaId=cursor.getString(1).orEmpty()
                        val file=cursor.getString(2).orEmpty().ifBlank{
                            cursor.getString(3).orEmpty()
                                .substringBefore('?').substringAfterLast('/')
                                .ifBlank{mediaId}
                        }
                        val raw=cursor.getString(4).orEmpty()
                        val state=when(raw){
                            "downloading"->State.ACTIVE
                            // Inventory/retry rows are NOT submitted
                            // download jobs. The old screen reported 350
                            // "queued" with zero active transfer services.
                            "pending","retrying"->State.HELD
                            else->State.FAILED
                        }
                        val updated=cursor.getLong(5)
                        rows+=Row("Gallery",file,
                            "Gallery · "+publisher+" · "+when(state){
                                State.ACTIVE->"Acquiring original"
                                State.HELD->"Unscheduled Gallery inventory; "+
                                    "choose this album or CHECK NEW to retry"
                                else->"Original unavailable; saved files kept"
                            },state,null,updated,{
                                (a as? AsterionHomeActivity)
                                    ?.openDownloadsModule("Gallery")
                            },mime="image/*",hasPrimaryAction=false,
                            onRemove=if(state==State.FAILED || state==State.HELD) ({
                                com.azrael.galleryflow.GalleryDb(
                                    a.applicationContext).use{db->
                                    db.cancelUnfinishedMedia(publisher,mediaId)
                                }
                            }) else null,
                            key="gallery:"+publisher+":"+mediaId)
                    }
                }
            }
        }.onFailure { android.util.Log.w(
            "AsterionDownloads","Gallery job listing unavailable",it) }
        // Pixiv and Kyora store successful originals in MediaStore rather
        // than the smaller transfer-job queues. Include recent native media
        // without loading the entire archive into memory.
        val projection=arrayOf(android.provider.BaseColumns._ID,
            android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_ADDED)
        val folders=listOf("Kuroha","PixiFlow","NocturnePixiv",
            "GalleryFlow","NocturneGallery","AsterionDownloader")
        val where=folders.joinToString(" OR "){
            android.provider.MediaStore.MediaColumns.RELATIVE_PATH+" LIKE ?"
        }
        runCatching{
            a.contentResolver.query(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,"("+where+") AND "+
                    android.provider.MediaStore.MediaColumns.SIZE+">0",
                folders.map{"%/"+it+"/%"}.toTypedArray(),
                android.provider.MediaStore.MediaColumns.DATE_ADDED+" DESC"
            )?.use{c->
                var found=0
                while(c.moveToNext() && found<70){
                    val name=c.getString(1).orEmpty()
                    val path=c.getString(2).orEmpty()
                    if(!name.endsWith(".jpg",true) &&
                        !name.endsWith(".png",true) &&
                        !name.endsWith(".webp",true) &&
                        !name.endsWith(".gif",true) &&
                        !name.endsWith(".jpeg",true))continue
                    val uri=android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        c.getLong(0)).toString()
                    val source=if(path.contains("gallery",true))"Gallery" else "Pixiv"
                    rows+=Row(source,name,source+" · Saved on device",
                        State.DONE,null,c.getLong(4)*1000L,{
                            open(a,uri,"image/*")
                        },uri,mime="image/*")
                    found++
                }
            }
        }
        val dm=a.getSystemService(DownloadManager::class.java)
        for(item in DirectDownloadTracker.states(a,180)){
            val original=if(item.status==DownloadManager.STATUS_SUCCESSFUL)
                runCatching{dm.getUriForDownloadedFile(item.job.id)?.toString()}.getOrNull()
                else null
            val present=readable(a,original)
            // DownloadManager can save a provider login page, redirect notice
            // or truncated archive and still report STATUS_SUCCESSFUL. Check
            // persisted bytes before showing a saved original in the queue.
            // Successful files are checked once at receipt time, not
            // re-read in full on EVERY 3-second screen refresh. An invalid
            // receipt stays invalid until a new real transfer is completed.
            val cachedReceipt=OriginalReceiptState.get(a,item.job.id)
            val receipt=if(item.status==DownloadManager.STATUS_SUCCESSFUL &&
                present && cachedReceipt !in setOf("VERIFIED","INVALID"))
                OriginalFileReceipt.verify(a,dm,item.job.id,
                    item.job.filename,item.total)
                else null
            if(receipt!=null)OriginalReceiptState.set(a,item.job.id,
                if(receipt.valid)"VERIFIED" else "INVALID")
            val validReceipt=present && when{
                cachedReceipt=="VERIFIED"->true
                cachedReceipt=="INVALID"->false
                else->receipt?.valid==true
            }
            val state=when {
                item.status==DownloadManager.STATUS_FAILED -> State.FAILED
                item.status==DownloadManager.STATUS_SUCCESSFUL &&
                    validReceipt -> State.DONE
                item.status==DownloadManager.STATUS_SUCCESSFUL -> State.FAILED
                item.status==DownloadManager.STATUS_RUNNING->State.ACTIVE
                item.status==DownloadManager.STATUS_PAUSED->State.HELD
                else->State.QUEUED
            }
            val percent=if(state==State.ACTIVE && item.total>0)
                (item.bytes*100L/item.total).toInt().coerceIn(0,100) else null
            rows+=Row(item.job.folder,item.job.filename,
                item.job.sourceHost+" · "+when(state){
                    State.DONE->"Saved on device"
                    State.FAILED->when {
                        OriginalReceiptState.get(a,item.job.id)=="STALLED" ->
                            "No bytes received for 150s · Stopped stalled OS request · Retry"
                        OriginalReceiptState.get(a,item.job.id)=="MISSING" ->
                            "Android request disappeared · Original unverified · Manual retry"
                        cachedReceipt=="INVALID" || receipt?.valid==false ->
                            "Rejected invalid original · "+
                                (receipt?.reason?.take(100)?:"Original integrity failed")
                        item.reason in 400..599->
                            "Provider HTTP "+item.reason+" · Connect account or retry"
                        item.reason>0 ->
                            "Android transfer failed · reason "+item.reason
                        else->"No accessible original file · retry or inspect source"
                    }
                    State.ACTIVE->"Android DownloadManager · "+item.bytes+
                        " bytes received"+if(item.total>0) " / "+item.total else ""
                    State.HELD->"Waiting for Android network / retry · "+
                        item.bytes+" bytes received"
                    else->"Queued with Android DownloadManager"
                },state,percent,item.job.created,{
                    when(state){
                        State.DONE->original?.let{open(a,it,a.contentResolver.getType(Uri.parse(it))?:"*/*")}
                        State.FAILED,State.HELD->{
                            val requested=DirectDownloadRetry.retryNow(a,item.job.id)
                            Toast.makeText(a,if(requested)
                                "Re-requesting original; watch actual received bytes"
                                else "Retry unavailable; reconnect source or resume network",
                                Toast.LENGTH_LONG).show()
                        }
                        else->{}
                    }
                },if(state==State.DONE)original else null,
                    actionText=if(state in setOf(State.FAILED,State.HELD))
                        "RETRY" else null,
                    onRemove=when(state){
                        State.ACTIVE,State.QUEUED,State.HELD->({
                            // OS DownloadManager owns this job; use its
                            // existing tracked cancel path so only this
                            // selected request is stopped and any verified
                            // completed original remains untouched.
                            val cancelled=DirectDownloadRetry.cancel(a,item.job.id)
                            if(cancelled)DownloadStateSignals.changed(a)
                            else Toast.makeText(a,
                                "Download changed; refresh before cancelling",
                                Toast.LENGTH_LONG).show()
                        })
                        State.FAILED->({
                            // Acknowledgement is durable across app restarts
                            // and stops automatic retries. Existing partial
                            // bytes and any completed original are not erased.
                            if(!DirectDownloadRetry.clearFailed(a,item.job.id))
                                Toast.makeText(a,
                                    "Transfer changed; refresh before clearing",
                                    Toast.LENGTH_LONG).show()
                        })
                        else->null
                    },
                    receivedBytes=item.bytes,totalBytes=item.total,
                    key="android:"+item.job.id)
        }
        // Integrate the existing isolated BitTorrent engine into the ONE
        // Downloads manager. The main process reads only its atomic receipts;
        // it never creates another libtorrent engine or fake file success.
        for(torrent in TorrentTransfers.snapshots(a,200)){
            if(torrent.stage=="CANCELLED")continue
            val state=when(torrent.stage){
                "TRANSFERRING","EXPORTING"->State.ACTIVE
                "EXPORTED"->State.DONE
                "FAILED"->State.FAILED
                "PAUSED"->State.PAUSED
                "QUARANTINED"->State.HELD
                else->State.QUEUED
            }
            val info=when(torrent.stage){
                "TRANSFERRING"->"Verified torrent pieces · "+
                    torrent.verifiedBytes+" / "+torrent.wantedBytes+
                    " bytes · "+torrent.peers+" peers"
                "EXPORTING"->"Copying piece-verified selected files to Downloads"
                "EXPORTED"->"Selected originals available in Downloads"
                "WAITING_PEERS"->"Waiting for peers · "+
                    torrent.peers+" connected; partial pieces preserved"
                "VERIFYING"->"Checking selected pieces in quarantine"
                "QUARANTINED"->"Pieces downloaded and hash-verified in private "+
                    "storage · NOT malware-scanned · review and export if trusted"
                else->torrent.text.substringAfter(" · ",torrent.text)
                    .take(250)
            }
            val onAction:()->Unit={
                if(torrent.stage=="QUARANTINED"){
                    TorrentVerifiedExport.request(a,torrent.id)
                }else if(torrent.stage=="EXPORTED"){
                    TorrentVerifiedExport.open(a,torrent.id)
                }else if(torrent.stage in setOf("QUEUED","WAITING_PEERS") &&
                    TorrentSafetyGate.incidentHold(a)){
                    // Only an actual previous reboot requires a separate
                    // owner-reviewed recovery dialog.
                    TorrentTransfers.showQueue(a)
                }else{
                val action=when(torrent.stage){
                    "TRANSFERRING"->TorrentTransferService.PAUSE
                    "PAUSED","FAILED","QUEUED","WAITING_PEERS"->
                        TorrentTransferService.RESUME
                    else->null
                }
                if(action!=null){
                    runCatching{
                        a.startForegroundService(
                            Intent(a,TorrentTransferService::class.java)
                                .setAction(action)
                                .putExtra("job",torrent.id))
                    }.onFailure{
                        Toast.makeText(a,"Torrent worker: "+
                            (it.message?:"Unable to start").take(120),
                            Toast.LENGTH_LONG).show()
                    }
                }
                }
            }
            rows+=Row("Torrents","Torrent "+torrent.id.take(8),
                info,state,
                if(torrent.wantedBytes>0L)
                    ((torrent.verifiedBytes.toDouble()/
                        torrent.wantedBytes)*100).toInt().coerceIn(0,100)
                else null,
                torrent.updated,onAction,
                mime="application/x-bittorrent",
                actionText=when(torrent.stage){
                    "TRANSFERRING"->"PAUSE"
                    "PAUSED","FAILED"->"RESUME"
                    "QUEUED","WAITING_PEERS"->"RESUME"
                    "QUARANTINED"->"REVIEW & EXPORT FILES"
                    "EXPORTED"->"OPEN"
                    else->null
                },
                receivedBytes=torrent.verifiedBytes,
                totalBytes=torrent.wantedBytes,
                hasPrimaryAction=torrent.stage in setOf(
                    "TRANSFERRING","PAUSED","FAILED","QUEUED","WAITING_PEERS",
                    "QUARANTINED","EXPORTED"),
                key="torrent:"+torrent.id,
                onRemove=if(torrent.stage in setOf(
                    "QUEUED","WAITING_PEERS","TRANSFERRING",
                    "PAUSED","FAILED","VERIFYING")) ({
                    // This calls the existing isolated torrent worker.
                    // No native peer engine is initialized by the UI. Keep
                    // quarantine payloads until explicit owner security review.
                    runCatching{
                        a.startForegroundService(
                            Intent(a,TorrentTransferService::class.java)
                                .setAction(TorrentTransferService.CANCEL)
                                .putExtra("job",torrent.id))
                    }.onFailure{
                        Toast.makeText(a,"Torrent cancellation unavailable: "+
                            (it.message?:"Service failed").take(120),
                            Toast.LENGTH_LONG).show()
                    }
                }) else null,
                transferStage=torrent.stage,
                transferUpdated=torrent.updated)
        }
        // Server-side cloud originals are native download jobs, not a
        // second local-download list. Expose only safe filenames, counts
        // and real cloud proof states. Never expose an encrypted URL.
        TeraBoxRemoteQueue(a).use{cloudQueue->
            for(record in cloudQueue.recent(200)){
                val source=when(record.category){
                    "videos"->"Video";"kyora"->"Gallery"
                    "x"->"X";"kuroha"->"Pixiv"
                    "comics"->"Comic ZIP";"books"->"EPUB"
                    "assets","koikatsu"->"Assets"
                    "games","torrents"->"Torrents"
                    "creators"->"Creators"
                    else->"Images"
                }
                val state=when(record.state){
                    "VERIFIED"->State.DONE
                    "ERROR"->State.FAILED
                    "PAUSED"->State.PAUSED
                    else->State.QUEUED
                }
                val info=when(record.state){
                    "VERIFIED"->"TeraBox remote file confirmed"
                    "SUBMITTED"->"TeraBox remote import accepted; verification pending"
                    "ERROR"->record.error?:"Cloud import failed"
                    "PAUSED"->"Cloud request paused"
                    else->"Queued for direct TeraBox import"
                }
                val actionText=when(record.state){
                    "ERROR"->"RETRY"
                    "PAUSED"->"RESUME"
                    "QUEUED"->"PAUSE"
                    else->null
                }
                // A remote verified receipt is NOT another local saved
                // original. Preserve the category in detail but track the
                // cloud queue under its own independent module.
                rows+=Row("TeraBox",record.filename,
                    "Remote "+source+" · "+info,state,null,
                    record.updatedAt,{
                        when(record.state){
                            "ERROR","PAUSED"->{
                                TeraBoxRemoteQueue(a).use{it.resume(record.id)}
                                NduCloudScheduler.requestImmediate(a)
                            }
                            "QUEUED"->TeraBoxRemoteQueue(a).use{
                                it.pauseQueued(record.id)
                            }
                            else->{}
                        }
                    },actionText=actionText,
                    hasPrimaryAction=actionText!=null,
                    onRemove=if(record.state in setOf(
                        "QUEUED","PAUSED","ERROR"))({
                        TeraBoxRemoteQueue(a).use{
                            it.cancelUnsubmitted(record.id)
                        }
                    }) else null,
                    key="terabox:"+record.id)
            }
        }
        val hidden=hiddenPrefs(a).getStringSet("keys",emptySet()).orEmpty()
        return rows.filterNot{it.state in setOf(State.DONE,State.PAUSED,
            State.FAILED,State.HELD) &&
            rowId(it) in hidden}.sortedWith(compareBy<Row>{
            when(it.state){
                State.ACTIVE->0;State.FAILED->1;State.HELD->2
                State.QUEUED->3;State.PAUSED->4;State.DONE->5
            }
        }.thenByDescending{it.updated})
    }
    /**
     * Full database counters are deliberately distinct from the bounded
     * "recent jobs" detail slice. A device with 750 failures cannot display
     * 70 as though 70 were the total. Keep the SQLite reads off the UI
     * thread. Older heterogeneous providers without a reliable total remain
     * visibly labelled "recent", not silently overcounted.
     */
    private data class Totals(val saved:Int=0,val failed:Int=0,
                              val queued:Int=0,val active:Int=0,
                              val paused:Int=0) {
        operator fun plus(other:Totals)=Totals(
            saved+other.saved,failed+other.failed,
            queued+other.queued,active+other.active,paused+other.paused)
    }
    private fun exactModuleTotals(a:Activity):Map<String,Totals>{
        val counts=LinkedHashMap<String,Totals>()
        fun aggregate(count:Map<String,Int>, saved:Set<String>,
                      failed:Set<String>,queued:Set<String>,
                      active:Set<String>,paused:Set<String>):Totals=
            Totals(saved.sumOf{count[it]?:0},failed.sumOf{count[it]?:0},
                queued.sumOf{count[it]?:0},active.sumOf{count[it]?:0},
                paused.sumOf{count[it]?:0})
        fun query(db:android.database.sqlite.SQLiteDatabase,table:String,
                  column:String):Map<String,Int>{
            val items=LinkedHashMap<String,Int>()
            db.rawQuery("SELECT "+column+",COUNT(*) FROM "+table+
                " GROUP BY "+column,null).use{c->
                while(c.moveToNext())items[c.getString(0).orEmpty()]=c.getInt(1)
            }
            return items
        }
        VideoDb(a).use{db->
            val found=query(db.readableDatabase,"videos","state")
            val listed=aggregate(found,
                setOf("done"),setOf("error","full_only_hold"),
                setOf("queued","retry_wait"),setOf("running"),setOf("paused"))
            // Full persisted count, not the 50 newest video rows. A former
            // DONE without readable positive bytes is a missing original,
            // never both Saved and Failed or a silently successful job.
            val corrected=DownloadOverviewPolicy.reconciledVideoCounts(
                listed.saved,listed.failed,db.missingSavedCount())
            counts["Video"]=listed.copy(
                saved=corrected.saved,failed=corrected.failed)
        }
        XPhotoQueue(a).use{db->
            counts["X"]=aggregate(db.counts(),setOf("done"),
                setOf("error"),setOf("queued","retry_wait"),
                setOf("running"),setOf("paused"))
        }
        ChapterQueue(a).use{db->
            val found=query(db.readableDatabase,"tasks","state")
            counts["Comic ZIP"]=aggregate(found,setOf("done"),
                setOf("error"),setOf("queued","retry"),
                setOf("working"),setOf("paused"))
        }
        NovelEpubQueue(a).use{db->
            val byType=LinkedHashMap<String,MutableMap<String,Int>>()
            db.readableDatabase.rawQuery(
                "SELECT url,state FROM novels",null).use{c->
                while(c.moveToNext()){
                    val kind=if(NovelEpubParser.readableZipSeries(
                        c.getString(0)))"Novel ZIP" else "EPUB"
                    val state=c.getString(1).orEmpty()
                    val values=byType.getOrPut(kind){LinkedHashMap()}
                    values[state]=(values[state]?:0)+1
                }
            }
            for((kind,values) in byType){
                counts[kind]=aggregate(values,setOf("DONE"),
                    setOf("ERROR","WAIT_LOGIN"),setOf("PENDING","RETRY"),
                    setOf("WORKING"),setOf("PAUSED"))
            }
        }
        // Authoritative Pixiv page receipts and Kyora media ledger.
        // The 70 newest MediaStore images in collect() are PREVIEW ROWS,
        // not a total. Never promote that bounded slice to a "Saved" count.
        AppDb(a).use{db->
            val sql=db.readableDatabase
            fun count(q:String):Int=android.database.DatabaseUtils.longForQuery(
                sql,q,null).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val sync=SyncControl.snapshot()
            counts["Pixiv"]=Totals(
                saved=count("SELECT COUNT(*) FROM pages WHERE saved=1"),
                failed=count("SELECT COUNT(*) FROM works "+
                    "WHERE state IN ('ERROR','FAILED')"),
                // Unsaved Pixiv pages are inventory, NOT queued transfers.
                // Only the existing SyncEngine owns acquisition; it has no
                // independently persisted per-page queue to count as jobs.
                // Reporting pages.saved=0 as "queued" fabricated thousands of
                // stuck downloads when sync was idle or disabled.
                queued=0,
                active=if(sync.running && !sync.paused &&
                    sync.mode!=SyncMode.CLOUD)1 else 0,
                paused=if(sync.running && sync.paused)1 else 0)
        }
        com.azrael.galleryflow.GalleryDb(a).use{db->
            val sql=db.readableDatabase
            fun count(q:String):Int=android.database.DatabaseUtils.longForQuery(
                sql,q,null).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            counts["Gallery"]=Totals(
                saved=count("SELECT COUNT(*) FROM media WHERE "+
                    "state='complete' AND content_uri IS NOT NULL "+
                    "AND bytes>0"),
                failed=count("SELECT COUNT(*) FROM media WHERE "+
                    "state IN ('permanent_error','inaccessible')"),
                // A Gallery media row is catalogue inventory, NOT an actual
                // OS transfer. Only durable selected/site requests count
                // as queued Gallery work (no more phantom 350 pending).
                queued=com.azrael.galleryflow.GalleryRequestLedger
                    .pending(a.applicationContext).size,
                active=count("SELECT COUNT(*) FROM media WHERE "+
                    "state='downloading'"))
        }
        // Include ALL server-side cloud records, not only the 200 newest
        // rows shown in Manage. Count submitted work as pending receipt,
        // never as a verified save.
        TeraBoxRemoteQueue(a).use{remote->
            remote.readableDatabase.rawQuery(
                "SELECT category,state,COUNT(*) FROM remote_originals "+
                    "WHERE state!='CANCELLED' GROUP BY category,state",
                null).use{c->
                while(c.moveToNext()){
                    val category=c.getString(0).orEmpty()
                    val state=c.getString(1).orEmpty()
                    val number=c.getInt(2)
                    val source=when(category){
                        "videos"->"Video";"kyora"->"Gallery"
                        "x"->"X";"kuroha"->"Pixiv"
                        "comics"->"Comic ZIP";"books"->"EPUB"
                        "assets","koikatsu"->"Assets"
                        "games","torrents"->"Torrents"
                        "creators"->"Creators"
                        else->"Images"
                    }
                    val contribution=when(state){
                        "VERIFIED"->Totals(saved=number)
                        "ERROR"->Totals(failed=number)
                        "PAUSED"->Totals(paused=number)
                        "QUEUED","SUBMITTED"->Totals(queued=number)
                        else->Totals()
                    }
                    counts["TeraBox"]=(counts["TeraBox"]?:Totals())+contribution
                }
            }
        }
        return counts
    }

    /** Structural states determine when buttons/actions must be rebound.
     * Bytes, speed, percentages and Saved counters do NOT change structure.
     * Preserve the actual View instances on every normal progress event
     * instead of rebuilding a static screenshot every few seconds.
     */
    private fun renderStructure(rows:List<Row>,counts:Map<String,Totals>,
                                expanded:String?):String=buildString {
        append(expanded.orEmpty())
        for(row in rows){
            if(row.state==State.DONE)continue
            append('|').append(rowId(row)).append(':')
                .append(row.state.name).append(':')
                .append(row.actionText).append(':')
                .append(row.hasPrimaryAction).append(':')
                .append(row.onRemove!=null)
        }
        for((source,stat) in counts.toSortedMap()){
            append('|').append(source).append('=')
                .append(stat.active).append(',')
                .append(stat.queued).append(',')
                .append(stat.failed).append(',')
                .append(stat.paused)
        }
    }

    private fun sameVisualTree(old:View,fresh:View):Boolean{
        if(old.javaClass!=fresh.javaClass)return false
        if(old is android.view.ViewGroup &&
            fresh is android.view.ViewGroup){
            if(old.childCount!=fresh.childCount)return false
            for(index in 0 until old.childCount)
                if(!sameVisualTree(old.getChildAt(index),
                        fresh.getChildAt(index)))return false
        }
        return true
    }

    private fun updateVisualValues(old:View,fresh:View){
        if(old is TextView && fresh is TextView){
            if(old.text!=fresh.text)old.text=fresh.text
            if(old.currentTextColor!=fresh.currentTextColor)
                old.setTextColor(fresh.currentTextColor)
            old.contentDescription=fresh.contentDescription
            old.isEnabled=fresh.isEnabled
        }
        if(old is ProgressBar && fresh is ProgressBar){
            old.max=fresh.max
            old.isIndeterminate=fresh.isIndeterminate
            if(!fresh.isIndeterminate)old.progress=fresh.progress
        }
        if(old is android.view.ViewGroup &&
            fresh is android.view.ViewGroup){
            for(index in 0 until old.childCount)
                updateVisualValues(old.getChildAt(index),
                    fresh.getChildAt(index))
        }
    }

    /** Refresh the Downloads content in place. The Activity, its global
     * navigation, scroll view and the existing cards remain alive while the
     * database is read. A refresh must never restart transfers or remove the
     * entire screen; stale results from a previous page are discarded.
     */
    @Suppress("UNUSED_PARAMETER")
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val surface=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        parent.addView(surface)
        val loading=ui.text(a,"Loading downloads…",13f,ui.secondary)
        surface.addView(loading,ui.lp(a,8))
        val inFlight=java.util.concurrent.atomic.AtomicBoolean(false)
        val missed=java.util.concurrent.atomic.AtomicBoolean(false)
        var previousStructure:String?=null
        lateinit var reload:()->Unit
        reload=reload@{
            if(a.isFinishing || a.isDestroyed || surface.parent!==parent)
                return@reload
            if(!inFlight.compareAndSet(false,true)){
                missed.set(true)
                return@reload
            }
            kotlin.concurrent.thread(name="asterion-downloads-read"){
                // A visual refresh is read-only: it must not reconcile or
                // restart queue agents merely because a user taps Refresh.
                val result=runCatching{
                    val rows=collect(a)
                    Triple(rows,exactModuleTotals(a),
                        NduCloudQueue(a).use{it.status()})
                }
                a.runOnUiThread{
                    inFlight.set(false)
                    if(a.isFinishing || a.isDestroyed || surface.parent!==parent)
                        return@runOnUiThread
                    if(missed.getAndSet(false))
                        surface.postDelayed({reload()},EVENT_COALESCE_MS)
                    result.onSuccess{snapshot->
                        val(rows,totals,cloud)=snapshot
                        recoverIdleQueues(a,rows)
                        val next=LinearLayout(a).apply{
                            orientation=LinearLayout.VERTICAL
                        }
                        show(a,next,reload,rows,totals,cloud)
                        val structure=renderStructure(rows,totals,
                            a.getSharedPreferences("asterion_downloads_ui",0)
                                .getString("module",null))
                        val previous=surface.getChildAt(0)
                        if(structure==previousStructure &&
                            previous!=null &&
                            sameVisualTree(previous,next)){
                            // Structural buttons and their listeners are still
                            // valid. Update text and progress IN PLACE;
                            // this is a continuously updating control panel,
                            // not a 3-second slideshow of replacement views.
                            updateVisualValues(previous,next)
                        }else{
                            surface.removeAllViews()
                            surface.addView(next)
                        }
                        previousStructure=structure
                    }.onFailure{error->
                        if(loading.parent===surface)
                            loading.text="Unable to load downloads: "+
                                error.message.orEmpty().take(140)
                        else Toast.makeText(a,"Refresh failed: "+
                            error.message.orEmpty().take(120),Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        reload()
        // Real transfer engines signal ContentService only AFTER durable
        // journal/database writes. A ContentObserver follows those events
        // across :videoflow_primary, :videoflow_secondary and :torrent
        // process boundaries. No static 3-second UI snapshot polling.
        //
        // Coalesce bursty byte events without starving busy transfers;
        // a 20-second reconciliation is ONLY a fallback for external
        // DownloadManager, Gallery or other sources lacking event hooks.
        val generation=java.util.concurrent.atomic.AtomicLong(0L)
        val listening=java.util.concurrent.atomic.AtomicBoolean(false)
        val eventScheduled=java.util.concurrent.atomic.AtomicBoolean(false)
        val pump=Runnable{
            eventScheduled.set(false)
            if(surface.parent===parent && surface.isAttachedToWindow)reload()
        }
        val observer=object:android.database.ContentObserver(
            android.os.Handler(android.os.Looper.getMainLooper())){
            override fun onChange(selfChange:Boolean){
                if(!a.isFinishing && !a.isDestroyed &&
                    surface.parent===parent && surface.isAttachedToWindow &&
                    eventScheduled.compareAndSet(false,true))
                    surface.postDelayed(pump,EVENT_COALESCE_MS)
            }
        }
        fun reconcile(token:Long){
            if(token!=generation.get() || a.isFinishing ||
                a.isDestroyed || surface.parent!==parent ||
                !surface.isAttachedToWindow)return
            reload()
            surface.postDelayed({reconcile(token)},RECONCILE_REFRESH_MS)
        }
        fun arm(){
            if(!listening.compareAndSet(false,true))return
            // This observer uses only a wake-up URI, no content is queried
            // from a provider or sent across process boundaries.
            runCatching {
                a.contentResolver.registerContentObserver(
                    DownloadStateSignals.uri(a),false,observer)
            }.onFailure{
                listening.set(false)
                android.util.Log.w("AsterionDownloads",
                    "Live observer registration failed; reconciliation retained",
                    it)
            }
            val token=generation.incrementAndGet()
            surface.postDelayed({reconcile(token)},RECONCILE_REFRESH_MS)
        }
        fun disarm(){
            generation.incrementAndGet()
            surface.removeCallbacks(pump)
            eventScheduled.set(false)
            if(listening.getAndSet(false))runCatching{
                a.contentResolver.unregisterContentObserver(observer)
            }
        }
        surface.addOnAttachStateChangeListener(
            object:View.OnAttachStateChangeListener{
                override fun onViewAttachedToWindow(v:View){arm();reload()}
                override fun onViewDetachedFromWindow(v:View){disarm()}
            })
        // Listener may be installed after this view was attached. Unlike
        // the old on-attach-only polling loop, no event can be missed.
        surface.post{
            if(surface.isAttachedToWindow && surface.parent===parent)arm()
        }
    }
    private fun thumbnail(a:Activity,view:ImageView,row:Row){
        val uri=row.saved
        if(uri?.startsWith("content:")==true && Build.VERSION.SDK_INT>=29 &&
            (row.mime=="image/*" || row.mime=="video/*")){
            thumbnails.execute{
                val bitmap=runCatching{
                    a.contentResolver.loadThumbnail(Uri.parse(uri),Size(160,160),null)
                }.getOrNull()
                if(bitmap!=null)a.runOnUiThread{
                    if(!a.isFinishing && !a.isDestroyed && view.isAttachedToWindow)
                        view.setImageBitmap(bitmap)
                }
            }
        } else if(row.image?.startsWith("https://")==true &&
                  ui.showPreviews(a)){
            RemoteImageLoader.load(a,view,row.image,row.image)
        }
    }
    /** A single module summary replaces repetitive per-file card matrices.
     * Item controls are available only after opening their own module.
     * Saved originals are excluded from bulk cancellation.
     */
    // Actual byte deltas between visible refreshes. Speed and ETA are never
    // invented from a progress percentage, file title, or expected bitrate.
    private val liveSamples=java.util.concurrent.ConcurrentHashMap<
        String,DownloadLiveRate.Sample>()

    private fun providerLabel(item:Row):String {
        if(item.source!="Video")return item.source
        val host=item.posterPage?.let{url->
            runCatching{java.net.URI(url).host.orEmpty().lowercase(
                java.util.Locale.ROOT).removePrefix("www.")}.getOrDefault("")
        }.orEmpty()
        return when(host){
            "jav.guru"->"JAV Guru"
            "javideo.net"->"JAVideo"
            "pornhub.com"->"Pornhub"
            "avple.tv"->"Avple"
            "pimpbunny.com"->"PimpBunny"
            "en.xchina.co","xchina.co"->"xChina"
            "bubblecum.lol"->"Bubblecum"
            "pornavhd.com"->"PornAVHD"
            "camstreams.tv"->"CamStreams"
            "livecamrips.to"->"LiveCamRips"
            "taiav.com"->"TaiAV"
            "porneec.com"->"Porneec"
            "archivebate.com"->"Archivebate"
            "uncensoredhentai.xxx"->"UncensoredHentai"
            "pornbuz.com"->"PornBuz"
            else->host.ifBlank{"Video"}
        }
    }
    private fun sizeLabel(bytes:Long):String {
        if(bytes<1_024L)return bytes.toString()+" B"
        val unit=when{
            bytes>=1_073_741_824L->1_073_741_824.0 to "GiB"
            bytes>=1_048_576L->1_048_576.0 to "MiB"
            else->1_024.0 to "KiB"
        }
        return String.format(java.util.Locale.US,"%.1f %s",
            bytes/unit.first,unit.second)
    }

    private fun speedLabel(item:Row):String? {
        val key=rowId(item)
        if(item.receivedBytes<=0L){
            liveSamples.remove(key)
            return null
        }
        if(liveSamples.size>80)liveSamples.clear()
        val now=android.os.SystemClock.elapsedRealtime()
        // Preserve the baseline through 240 ms ContentObserver events.
        // Replacing it on EVERY event made elapsed<600 ms indefinitely,
        // suppressing real transfer speeds and parallel-progress evidence.
        val next=DownloadLiveRate.measure(
            liveSamples[key],item.receivedBytes,now)
        liveSamples[key]=next
        return next.rateBytesPerSecond?.let{sizeLabel(it)+"/s"}
    }

    /** Perform queue commands on the Activity thread in bounded batches.
     * Some legacy callbacks interact with Android UI/foreground services,
     * and invoking those on a raw background Thread causes crashes.
     */
    private fun applyModuleActions(a:Activity,actions:List<()->Unit>,
                                   refresh:()->Unit){
        val iterator=actions.iterator()
        fun nextBatch(){
            if(a.isFinishing || a.isDestroyed)return
            repeat(4){
                if(iterator.hasNext())runCatching{iterator.next().invoke()}
            }
            if(iterator.hasNext())
                a.window.decorView.post{nextBatch()}
            else refresh()
        }
        nextBatch()
    }

    private fun metric(a:Activity,label:String,count:Int,
                       warning:Boolean=false):LinearLayout =
        LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL
            background=ui.shape(a,ui.raised,16,ui.border)
            setPadding(ui.px(a,15),ui.px(a,12),
                ui.px(a,10),ui.px(a,13))
            addView(ui.text(a,count.toString(),24f,
                if(warning && count>0)ui.danger else ui.white,true))
            addView(ui.text(a,label,12f,ui.secondary),ui.lp(a,2))
            contentDescription=label+": "+count
        }

    /** Merged Control Center and Live Queue. Only active non-image files
     * receive an individual live row. Images, saved files, queued files and
     * failures remain aggregate module counts, not a scrolling file matrix.
     */
    private fun show(a:Activity,parent:LinearLayout,refresh:()->Unit,
                     rows:List<Row>,exact:Map<String,Totals>,
                     cloud:NduCloudQueue.Status){
        val prefs=a.getSharedPreferences("asterion_downloads_ui",0)
        val expanded=prefs.getString("module",null)
        val grouped=rows.groupBy{it.source}
        fun totalsFor(source:String):Totals=exact[source]?:run {
            val items=grouped[source].orEmpty()
            Totals(
                saved=items.count{it.state==State.DONE},
                failed=items.count{it.state in setOf(State.FAILED,State.HELD)},
                queued=items.count{it.state==State.QUEUED},
                active=items.count{it.state==State.ACTIVE},
                paused=items.count{it.state==State.PAUSED})
        }
        fun nonEmpty(t:Totals)=t.saved+t.failed+t.queued+t.active+t.paused>0
        // Source rows are capped for performance. The overview MUST still
        // include modules whose full-database counters exceed that slice.
        val sources=(grouped.keys+exact.keys).distinct()
            .filter{nonEmpty(totalsFor(it))}
            .sortedWith(compareByDescending<String>{totalsFor(it).active}
                .thenByDescending{totalsFor(it).failed}
                .thenByDescending{totalsFor(it).queued}
                .thenBy{it.lowercase()})
        // Never count a remote cloud proof twice as an original stored
        // locally. TeraBox has its own module counters and progress.
        val all=sources.filter{it!="TeraBox"}.map{totalsFor(it)}
            .fold(Totals()){acc,next->acc+next}

        parent.addView(ui.text(a,"Downloads",23f,ui.white,true),
            ui.lp(a,8))
        val metrics=ui.row(a).apply{gravity=Gravity.TOP}
        // A claimed video can be resolving a publisher, not transferring.
        // The overview reports work; the live panel requires actual bytes.
        val topLeft=metric(a,"In progress",all.active)
        val topRight=metric(a,"Queued",all.queued)
        metrics.addView(topLeft,LinearLayout.LayoutParams(0,-2,1f).apply{
            rightMargin=ui.px(a,5)
        })
        metrics.addView(topRight,LinearLayout.LayoutParams(0,-2,1f).apply{
            leftMargin=ui.px(a,5)
        })
        parent.addView(metrics,ui.lp(a,5))
        val second=ui.row(a).apply{gravity=Gravity.TOP}
        second.addView(metric(a,"Needs attention",all.failed,true),
            LinearLayout.LayoutParams(0,-2,1f).apply{
                rightMargin=ui.px(a,5)
            })
        second.addView(metric(a,"Saved",all.saved),
            LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=ui.px(a,5)
            })
        parent.addView(second,ui.lp(a,5))
        if(!AppPower.enabled(a))parent.addView(
            ui.action(a,"RESUME DOWNLOADS",true){
                AppPower.set(a,true);refresh()
            },ui.lp(a,9))

        // A truly live file (not an image, not an incomplete cloud handoff,
        // not a paused/queued item) is the only individual row shown by default.
        // All active non-image originals remain visible from preparation to
        // verification, even when the peer/server is idle or speed is unknown.
        // 'Flowing' below is a STRICT measured byte delta, never inferred
        // from a running state, progress stage or number of worker threads.
        val live=rows.filter{row->
            DownloadOverviewPolicy.isLiveFile(
                row.state.name,row.source,row.filename,row.mime)
        }.sortedByDescending{it.updated}
        val resolving=live.count{row->
            row.source=="Video" &&
                row.transferStage !in setOf("receiving","fragments")
        }
        // A concurrent-thread count is not proof of parallel downloads.
        // Only two independent IDs whose actual byte counters advanced
        // since the preceding refresh count as measured parallel transfers.
        val observedRates=live.associate{row->
            rowId(row) to speedLabel(row)
        }
        val byteAdvancing=observedRates.values.count{it!=null}
        val liveHeader=ui.row(a)
        liveHeader.addView(ui.text(a,"Live transfers",16f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        liveHeader.addView(ui.text(a,byteAdvancing.toString()+" flowing"+
            (if(resolving>0)" · $resolving resolving" else ""),
            11f,ui.secondary),LinearLayout.LayoutParams(-2,-2))
        parent.addView(liveHeader,ui.lp(a,16,8))
        if(byteAdvancing>=2)
            parent.addView(ui.text(a,
                "Parallel confirmed · "+byteAdvancing+
                    " independent files gaining bytes",11f,ui.primary),
                ui.lp(a,0,4))
        val livePanel=ui.card(a,14)
        if(live.isEmpty()){
            livePanel.addView(ui.text(a,"No file downloads running",
                13f,ui.secondary))
        }else{
            for((index,item) in live.withIndex()){
                if(index>0){
                    val line=View(a).apply{
                        setBackgroundColor(ui.border)
                    }
                    livePanel.addView(line,ui.lp(a,11,11).apply{
                        height=ui.px(a,1)
                    })
                }
                val heading=ui.row(a)
                // Load only the selected publisher page's real OG poster.
                // Never use random search images or the video URL as an image.
                item.posterPage?.let{page->
                    val preview=ImageView(a).apply{
                        scaleType=ImageView.ScaleType.CENTER_INSIDE
                        setBackgroundColor(ui.raised)
                    }
                    heading.addView(preview,LinearLayout.LayoutParams(
                        ui.px(a,64),ui.px(a,64)).apply{
                        rightMargin=ui.px(a,9)
                    })
                    VideoSearchPreviews.display(a,preview,null,page)
                }
                val name=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                }
                name.addView(ui.text(a,providerLabel(item),11f,ui.secondary,true))
                name.addView(ui.text(a,item.filename,14f,ui.white,true).apply{
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                },ui.lp(a,2))
                heading.addView(name,LinearLayout.LayoutParams(0,-2,1f))
                val canPause=item.hasPrimaryAction &&
                    item.actionText=="PAUSE"
                val canCancel=item.onRemove!=null
                if(canPause || canCancel){
                    heading.addView(ui.action(a,"⋮",false){
                        val choices=ArrayList<String>()
                        if(canPause)choices.add("Pause transfer")
                        if(canCancel)choices.add("Cancel transfer")
                        android.app.AlertDialog.Builder(a)
                            .setTitle(item.filename.take(80))
                            .setItems(choices.toTypedArray()){_,selected->
                                when(choices[selected]){
                                    "Pause transfer"->{
                                        item.action()
                                        refresh()
                                    }
                                    "Cancel transfer"->{
                                        android.app.AlertDialog.Builder(a)
                                            .setTitle("Cancel this transfer?")
                                            .setMessage("Saved originals are preserved.")
                                            .setNegativeButton("Keep",null)
                                            .setPositiveButton("Cancel"){_,_->
                                                item.onRemove?.invoke()
                                                refresh()
                                            }.show()
                                    }
                                }
                            }.show()
                    },LinearLayout.LayoutParams(
                        ui.px(a,48),-2).apply{
                        leftMargin=ui.px(a,8)
                    })
                }
                livePanel.addView(heading)
                // A video percentage is shown ONLY when it has an
                // authoritative HLS fragment denominator; the extractor's
                // stage percentage is never mistaken for transfer completion.
                // Files with unknown total size stay indeterminate.
                val validProgress=item.percent?.coerceIn(0,100)
                val bar=ProgressBar(a,null,
                    android.R.attr.progressBarStyleHorizontal).apply{
                    max=100
                    isIndeterminate=validProgress==null
                    if(validProgress!=null)progress=validProgress
                    progressTintList=ColorStateList.valueOf(ui.primary)
                    indeterminateTintList=ColorStateList.valueOf(ui.primary)
                    progressBackgroundTintList=ColorStateList.valueOf(ui.border)
                }
                livePanel.addView(bar,ui.lp(a,9).apply{
                    height=ui.px(a,5)
                })
                val speed=observedRates[rowId(item)]
                val details=ArrayList<String>()
                if(validProgress!=null)
                    details.add(validProgress.toString()+"% of known file")
                else if(item.reportedProgress!=null &&
                    item.reportedProgress>0)
                    details.add(item.reportedProgress.toString()+
                        "% reported by extractor · final size unknown")
                if(item.receivedBytes>0L)
                    details.add(sizeLabel(item.receivedBytes)+" received")
                if(speed!=null){
                    details.add(speed)
                    // Only an advancing rate and a genuine declared byte
                    // total permit an ETA. Never derive this from publisher
                    // bitrates, extractor stage percents or unknown HLS size.
                    val eta=DownloadLiveRate.etaSeconds(
                        item.receivedBytes,item.totalBytes,
                        liveSamples[rowId(item)]?.rateBytesPerSecond)
                    if(eta!=null)details.add("~"+DownloadLiveRate.etaLabel(eta)+
                        " remaining")
                }
                if(item.source=="Video"){
                    val stage=when(item.transferStage){
                        "receiving","fragments"->"Receiving original"
                        "verification","verifying"->"Verifying media"
                        "transfer"->"Connecting to source"
                        "python_fallback"->"Trying alternate extractor"
                        "native_deferred"->"Waiting for primary extractor"
                        else->"Resolving selected publisher"
                    }
                    details.add(0,stage)
                }
                if(details.isEmpty())
                    details.add(item.info.substringAfter(" · ",item.info)
                        .ifBlank{"Connecting to original"}.take(110))
                livePanel.addView(ui.text(a,details.joinToString(" · "),
                    11f,ui.secondary),ui.lp(a,6))
            }
        }
        parent.addView(livePanel,ui.lp(a,0,7))
        parent.addView(ui.text(a,"Modules",16f,ui.white,true),
            ui.lp(a,15,7))
        if(sources.isEmpty()){
            parent.addView(ui.text(a,"No tracked downloads",
                12f,ui.secondary),ui.lp(a,5))
            return
        }
        for(source in sources){
            val items=grouped[source].orEmpty()
            val totals=totalsFor(source)
            val opened=source==expanded
            val card=ui.card(a,12)
            val header=ui.row(a)
            val title=LinearLayout(a).apply{
                orientation=LinearLayout.VERTICAL
            }
            title.addView(ui.text(a,source,15f,ui.white,true))
            val counts=ArrayList<String>()
            counts.add(totals.saved.toString()+" saved")
            if(totals.active>0)counts.add(totals.active.toString()+" active")
            if(totals.queued>0)counts.add(totals.queued.toString()+" queued")
            if(totals.failed>0)counts.add(totals.failed.toString()+" failed")
            if(totals.paused>0)counts.add(totals.paused.toString()+" paused")
            title.addView(ui.text(a,counts.joinToString(" · "),
                11f,if(totals.failed>0)ui.danger else ui.secondary)
                .apply{
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                },ui.lp(a,3))
            header.addView(title,LinearLayout.LayoutParams(0,-2,1f))
            header.addView(ui.action(a,if(opened)"CLOSE" else "MANAGE",
                false){
                prefs.edit().putString("module",
                    if(opened)null else source).apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{
                leftMargin=ui.px(a,7)
            })
            card.addView(header)
            if(opened){
                if(source in setOf("Pixiv","Gallery","X","Torrents",
                    "Video","Comic ZIP","EPUB","Novel ZIP","Assets","Creators")){
                    card.addView(ui.action(a,
                        "OPEN "+source.uppercase(java.util.Locale.ROOT)+
                            " MODULE",false){
                        (a as? AsterionHomeActivity)
                            ?.openDownloadsModule(source)
                    },ui.lp(a,9))
                }
                if(source=="Gallery"){
                    val waiting=runCatching{
                        com.azrael.galleryflow.GalleryDb(a).use{db->
                            val sql=db.readableDatabase
                            val retry=android.database.DatabaseUtils.longForQuery(
                                sql,"SELECT COUNT(*) FROM backfill_retries",null)
                            val media=android.database.DatabaseUtils.longForQuery(
                                sql,"SELECT COUNT(*) FROM media WHERE "+
                                    "content_uri IS NULL AND state IN "+
                                    "('pending','retrying','permanent_error','inaccessible')",
                                null)
                            (retry+media).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        }
                    }.getOrDefault(totals.queued+totals.failed)
                    if(waiting>0){
                        card.addView(ui.action(a,"CANCEL ALL WAITING GALLERY JOBS",false){
                            android.app.AlertDialog.Builder(a)
                                .setTitle("Cancel waiting Gallery jobs?")
                                .setMessage("Cancel all "+waiting+
                                    " queued or failed Gallery originals, not only "+
                                    "the most recent 80 displayed. Stop the current "+
                                    "Gallery scan first. Existing saved files, "+
                                    "previews and historical records are preserved.")
                                .setNegativeButton("Keep jobs",null)
                                .setPositiveButton("Cancel waiting"){_,_->
                                    com.azrael.galleryflow.SyncControl.stop()
                                    com.azrael.galleryflow.GalleryRequestLedger.clear(
                                        a.applicationContext)
                                    runCatching{
                                        a.startForegroundService(
                                            Intent(a,com.azrael.galleryflow
                                                .SyncForegroundService::class.java)
                                                .setAction(com.azrael.galleryflow
                                                    .SyncForegroundService.ACTION_STOP))
                                    }
                                    val cancelled=runCatching{
                                        com.azrael.galleryflow.GalleryDb(
                                            a.applicationContext).use{
                                            it.cancelAllWaitingMedia()
                                        }
                                    }.getOrDefault(0)
                                    Toast.makeText(a,
                                        cancelled.toString()+" waiting Gallery "+
                                            "retry/inventory records cancelled; saved files kept",
                                        Toast.LENGTH_LONG).show()
                                    refresh()
                                }.show()
                        },ui.lp(a,8))
                    }
                }
                if(source=="Gallery"){
                    val sync=com.azrael.galleryflow.SyncControl.snapshot()
                    if(sync.running && !sync.stopping){
                        card.addView(ui.action(a,"STOP GALLERY SYNC",false){
                            android.app.AlertDialog.Builder(a)
                                .setTitle("Stop Gallery downloads?")
                                .setMessage("Stop the current Gallery sync. "+
                                    "Already saved originals remain intact; "+
                                    "unfinished gallery entries may be resumed "+
                                    "through their creator or website.")
                                .setNegativeButton("Keep running",null)
                                .setPositiveButton("Stop sync"){_,_->
                                    com.azrael.galleryflow.SyncControl.stop()
                                    DownloadStateSignals.changed(a)
                                    refresh()
                                }.show()
                        },ui.lp(a,8))
                    }
                }
                if(source=="Pixiv"){
                    val sync=SyncControl.snapshot()
                    if(sync.running && !sync.stopping){
                        card.addView(ui.action(a,
                            if(sync.paused)"RESUME PIXIV SYNC" else
                                "PAUSE PIXIV SYNC",false){
                            if(sync.paused)SyncControl.resume()
                            else SyncControl.pause()
                            DownloadStateSignals.changed(a)
                            refresh()
                        },ui.lp(a,7))
                    }
                }
                // 'Manage' must perform actual operations. Show the selected
                // module's unfinished durable jobs and contextual actions
                // rather than merely toggling its heading to 'Close'.
                val failures=items.filter{
                    it.state in setOf(State.FAILED,State.HELD)
                }
                val chosenFilter=selectedFilter(prefs,source)
                val available=items.filter{matchesFilter(it.state,chosenFilter)}
                val stateCounts=items.groupingBy{it.state}.eachCount()
                // One compact filter selector replaces six full-size
                // horizontal chips while keeping every existing state.
                val availableFilters=Filter.entries.filter{filter->
                    filter==Filter.ALL || (if(filter==Filter.DONE)
                        totals.saved>0 else
                        (stateCounts[State.valueOf(filter.name)]?:0)>0)
                }
                card.addView(ui.action(a,
                    "SHOW: "+chosenFilter.label.uppercase(java.util.Locale.ROOT)+
                    " · CHANGE FILTER",false){
                    android.app.AlertDialog.Builder(a)
                        .setTitle(source+" downloads")
                        .setSingleChoiceItems(
                            availableFilters.map{it.label}.toTypedArray(),
                            availableFilters.indexOf(chosenFilter)){dialog,index->
                            prefs.edit().putString("filter:"+source,
                                availableFilters[index].name).apply()
                            dialog.dismiss()
                            refresh()
                        }.setNegativeButton("Close",null).show()
                },ui.lp(a,5))
                val pageKey="page:"+source+":"+chosenFilter.name
                val pageSize=prefs.getInt(pageKey,8).coerceIn(6,180)
                val managed=available.take(pageSize)
                if(source=="Video" && totals.failed>0){
                    card.addView(ui.action(a,
                        "CLEAR "+totals.failed+" FAILED · KEEP FILES",false){
                        android.app.AlertDialog.Builder(a)
                            .setTitle("Clear failed video jobs?")
                            .setMessage("This acknowledges terminal failures "+
                                "and removes their red download alerts. "+
                                "Saved originals and the original error "+
                                "records remain on the phone. "+
                                "Queued, active and paused jobs are not touched.")
                            .setNegativeButton("Keep",null)
                            .setPositiveButton("Clear failures"){_,_->
                                kotlin.concurrent.thread(
                                    name="asterion-clear-failed-video-receipts"){
                                    val cleaned=runCatching{
                                        VideoDb(a.applicationContext).use{db->
                                            db.clearTerminalFailures()+
                                                db.acknowledgeAllMissingSaved()
                                        }
                                    }
                                    a.runOnUiThread{
                                        if(a.isFinishing || a.isDestroyed)
                                            return@runOnUiThread
                                        cleaned.onFailure{
                                            Toast.makeText(a,
                                                "Could not clear every failed job",
                                                Toast.LENGTH_LONG).show()
                                        }
                                        DownloadStateSignals.changed(a)
                                        refresh()
                                    }
                                }
                            }.show()
                    },ui.lp(a,9))
                }
                if(source!="Video"){
                    val recentFailures=items.filter{it.state==State.FAILED}
                    if(recentFailures.isNotEmpty()){
                        card.addView(ui.action(a,
                            "CLEAR "+recentFailures.size+
                                " RECENT FAILED · KEEP SAVED FILES",false){
                            android.app.AlertDialog.Builder(a)
                                .setTitle("Clear "+source+" failures?")
                                .setMessage("Dismiss "+recentFailures.size+
                                    " recent failed jobs. Saved files and "+
                                    "downloaded chapters are retained; "+
                                    "additional older failures may remain.")
                                .setNegativeButton("Keep",null)
                                .setPositiveButton("Clear failures"){_,_->
                                    applyModuleActions(a,
                                        recentFailures.map{job->
                                            {clearFailedJob(a,job)}
                                        },refresh)
                                }.show()
                        },ui.lp(a,8))
                    }
                }
                if(managed.isNotEmpty()){
                    card.addView(ui.text(a,
                        chosenFilter.label+" · "+managed.size+" of "+
                            available.size+" recent tracked jobs",
                        12f,ui.secondary,true),ui.lp(a,10,4))
                    for(job in managed){
                        val panel=LinearLayout(a).apply{
                            orientation=LinearLayout.VERTICAL
                            setPadding(0,ui.px(a,8),0,ui.px(a,8))
                        }
                        val name=ui.text(a,job.filename.take(95),
                            13f,ui.white,true).apply{
                            maxLines=2
                            ellipsize=android.text.TextUtils.TruncateAt.END
                        }
                        val poster=job.posterPage
                        if(poster!=null){
                            val heading=ui.row(a).apply{
                                gravity=Gravity.CENTER_VERTICAL
                            }
                            val thumbnail=ImageView(a).apply{
                                scaleType=ImageView.ScaleType.CENTER_INSIDE
                                setBackgroundColor(ui.raised)
                            }
                            heading.addView(thumbnail,LinearLayout.LayoutParams(
                                ui.px(a,58),ui.px(a,58)).apply{
                                rightMargin=ui.px(a,8)
                            })
                            val identity=LinearLayout(a).apply{
                                orientation=LinearLayout.VERTICAL
                                addView(ui.text(a,providerLabel(job),
                                    11f,ui.secondary,true))
                                addView(name,ui.lp(a,2))
                            }
                            heading.addView(identity,LinearLayout.LayoutParams(0,-2,1f))
                            panel.addView(heading)
                            VideoSearchPreviews.display(a,thumbnail,null,poster)
                        }else{
                            panel.addView(ui.text(a,providerLabel(job),
                                11f,ui.secondary,true))
                            panel.addView(name)
                        }
                        val stateDescription=when(job.state){
                            State.ACTIVE->"Working"
                            State.QUEUED->"Queued"
                            State.PAUSED->"Paused"
                            State.FAILED->"Failed"
                            State.HELD->"Needs action"
                            State.DONE->"Saved"
                        }
                        val progress=job.percent?.let{" · $it% original" }
                            ?:job.reportedProgress?.takeIf{it>0}
                                ?.let{" · $it% extractor progress"}
                            ?:if(job.source=="Torrents" &&
                                job.totalBytes>0L)
                                " · "+((job.receivedBytes.toDouble()/
                                    job.totalBytes)*100).toInt()
                                    .coerceIn(0,100)+"% verified"
                            else ""
                        val bytes=if(job.receivedBytes>0)
                            " · "+sizeLabel(job.receivedBytes) else ""
                        panel.addView(ui.text(a,
                            stateDescription+progress+bytes+" · "+
                                job.info.substringAfter(" · ",job.info).take(105),
                            11f,if(job.state in setOf(State.FAILED,State.HELD))
                                ui.danger else ui.secondary),ui.lp(a,5))
                        val actions=ui.row(a)
                        if(job.hasPrimaryAction && job.actionText!=null &&
                            job.actionText !in setOf("COOLDOWN")){
                            actions.addView(ui.action(a,job.actionText,
                                job.actionText=="RESUME"){
                                job.action();refresh()
                            },LinearLayout.LayoutParams(0,-2,1f))
                        }
                        if(job.state==State.FAILED){
                            actions.addView(ui.action(a,"CLEAR",false){
                                clearFailedJob(a,job)
                                refresh()
                            },LinearLayout.LayoutParams(0,-2,1f))
                        }else if(job.onRemove!=null &&
                            job.state in setOf(State.ACTIVE,State.QUEUED,
                                State.PAUSED,State.HELD,State.DONE)){
                            actions.addView(ui.action(a,
                                if(job.state==State.DONE)"HIDE"
                                else if(job.state==State.ACTIVE)"CANCEL"
                                else "DISCARD",false){
                                if(job.state==State.ACTIVE){
                                    android.app.AlertDialog.Builder(a)
                                        .setTitle("Cancel this transfer?")
                                        .setMessage("Completed originals and "+
                                            "saved partials are retained.")
                                        .setNegativeButton("Keep",null)
                                        .setPositiveButton("Cancel"){_,_->
                                            job.onRemove.invoke()
                                            refresh()
                                        }.show()
                                }else{
                                    job.onRemove.invoke()
                                    refresh()
                                }
                            },LinearLayout.LayoutParams(0,-2,1f))
                        }
                        if(actions.childCount>0)
                            panel.addView(actions,ui.lp(a,6))
                        card.addView(panel,ui.lp(a,5))
                    }
                }else{
                    card.addView(ui.text(a,
                        "No "+chosenFilter.label.lowercase()+
                            " jobs in the recent queue sample. "+
                            "Module totals include durable history.",
                        12f,ui.secondary),ui.lp(a,10))
                }
                if(available.size>managed.size){
                    card.addView(ui.action(a,
                        "SHOW MORE · "+(available.size-managed.size)+
                            " additional tracked jobs",false){
                        prefs.edit().putInt(pageKey,
                            (pageSize+24).coerceAtMost(180)).apply()
                        refresh()
                    },ui.lp(a,7))
                }else if(pageSize>16){
                    card.addView(ui.action(a,"SHOW FEWER",false){
                        prefs.edit().putInt(pageKey,16).apply()
                        refresh()
                    },ui.lp(a,7))
                }
                // Scoped bulk controls retain the existing engine operations.
                val pausable=items.filter{
                    it.state in setOf(State.ACTIVE,State.QUEUED) &&
                    it.actionText in setOf("PAUSE","HOLD")
                }
                val resumable=items.filter{
                    it.state==State.PAUSED && it.actionText=="RESUME"
                }
                val cancellable=items.filter{
                    it.state in setOf(State.ACTIVE,State.QUEUED,State.PAUSED) &&
                        it.onRemove!=null
                }
                // A single visible job already exposes RESUME on its row.
                // Rendering the same operation again as a full-width bulk
                // button caused the duplicated Torrent RESUME in the report.
                val bulkResumable=resumable.size>1
                if(pausable.isNotEmpty() || bulkResumable ||
                    cancellable.isNotEmpty()){
                    val actions=ui.row(a)
                    if(pausable.isNotEmpty())
                        actions.addView(ui.action(a,"PAUSE",false){
                            // Callbacks are UI-facing (some launch Activities
                            // or notifications). Never invoke them from an
                            // unsafe raw worker thread.
                            applyModuleActions(a,pausable.map{it.action},refresh)
                        },LinearLayout.LayoutParams(0,-2,1f))
                    if(bulkResumable)
                        actions.addView(ui.action(a,"RESUME",true){
                            if(source=="Video"){
                                VideoDb(a).use{db->
                                    resumable.forEach{row->
                                        row.key?.removePrefix("video:")
                                            ?.toLongOrNull()?.let{db.retry(it)}
                                    }
                                }
                                VideoEngine.launch(a.applicationContext,false)
                                refresh()
                            }else applyModuleActions(
                                a,resumable.map{it.action},refresh)
                        },LinearLayout.LayoutParams(0,-2,1f))
                    if(cancellable.isNotEmpty())
                        actions.addView(ui.action(a,"CANCEL",false){
                            android.app.AlertDialog.Builder(a)
                                .setTitle("Cancel "+source+" unfinished jobs?")
                                .setMessage(cancellable.size.toString()+
                                    " tracked jobs will be cancelled. "+
                                    "Saved originals are preserved.")
                                .setNegativeButton("Keep",null)
                                .setPositiveButton("Cancel jobs"){_,_->
                                    applyModuleActions(a,
                                        cancellable.mapNotNull{it.onRemove},
                                        refresh)
                                }.show()
                        },LinearLayout.LayoutParams(0,-2,1f))
                    card.addView(actions,ui.lp(a,9))
                }
                if(failures.isNotEmpty()){
                    card.addView(ui.action(a,
                        "ISSUES · "+totals.failed,true){
                        val options=failures.map{item->
                            item.filename.take(70)+" · "+
                                item.info.substringAfter(" · ",item.info).take(75)
                        }.toTypedArray()
                        android.app.AlertDialog.Builder(a)
                            .setTitle(source+" · needs attention")
                            .setItems(options){_,selected->
                                val failure=failures[selected]
                                val reason=failure.info.substringAfter(" · ",
                                    failure.info).take(2000)
                                val choices=ArrayList<String>()
                                if(failure.hasPrimaryAction)choices.add(
                                    failure.actionText?:"Retry original")
                                choices.add("Keep for later")
                                choices.add("Clear failure · keep saved files")
                                if(failure.onRemove!=null)
                                    choices.add("Discard failed job")
                                android.app.AlertDialog.Builder(a)
                                    .setTitle(failure.filename.take(110))
                                    .setMessage(reason)
                                    .setItems(choices.toTypedArray()){_,i->
                                        when(choices[i]){
                                            "Discard failed job"->{
                                                failure.onRemove?.invoke()
                                                refresh()
                                            }
                                            "Clear failure · keep saved files"->{
                                                clearFailedJob(a,failure)
                                                refresh()
                                            }
                                            "Keep for later"->{}
                                            else->{failure.action();refresh()}
                                        }
                                    }.setNegativeButton("Close",null).show()
                            }.setNegativeButton("Close",null).show()
                    },ui.lp(a,9))
                }
            }
            parent.addView(card,ui.lp(a,0,7))
        }
        if(NduSettings.cloudOnly(a) &&
            (cloud.pending>0 || cloud.completed>0)){
            parent.addView(ui.text(a,
                "TeraBox · "+cloud.completed+" verified · "+
                    cloud.pending+" awaiting proof",
                11f,ui.secondary),ui.lp(a,8))
        }
    }
}

/** Pure byte-rate sampler for the existing event-driven Downloads screen.
 * Sub-second observer bursts must not reset its measurement window.
 * A stalled, restarted or rolled-back transfer cannot display stale speed.
 */
internal object DownloadLiveRate {
    fun etaSeconds(receivedBytes:Long,totalBytes:Long,
                   rateBytesPerSecond:Long?):Long? {
        val rate=rateBytesPerSecond?:return null
        if(receivedBytes<=0L || totalBytes<=receivedBytes ||
            rate<=0L || totalBytes<=0L)return null
        val remaining=totalBytes-receivedBytes
        val seconds=remaining/rate+if(remaining%rate>0L)1L else 0L
        // A week-long projection from a burst is too uncertain to display.
        return seconds.takeIf{it in 1L..604_800L}
    }

    fun etaLabel(seconds:Long):String = when {
        seconds<60L->"${seconds}s"
        seconds<3600L->"${seconds/60}m ${seconds%60}s"
        seconds<86400L->"${seconds/3600}h ${seconds%3600/60}m"
        else->"${seconds/86400}d ${seconds%86400/3600}h"
    }
    data class Sample(
        val bytes:Long,
        val baselineTimeMs:Long,
        val rateBytesPerSecond:Long?=null,
        val rateMeasuredAtMs:Long=0L
    )
    internal fun measure(previous:Sample?,bytes:Long,nowMs:Long):Sample {
        require(bytes>=0L && nowMs>=0L)
        if(previous==null || bytes<previous.bytes ||
            nowMs<previous.baselineTimeMs)
            return Sample(bytes,nowMs)
        val elapsed=nowMs-previous.baselineTimeMs
        if(elapsed<800L) {
            // An already measured rate can remain visible between UI
            // frames, but expires if the download stops advancing.
            return if(previous.rateBytesPerSecond!=null &&
                nowMs-previous.rateMeasuredAtMs>2_000L)
                previous.copy(rateBytesPerSecond=null)
            else previous
        }
        val gained=bytes-previous.bytes
        if(gained<=0L || elapsed>30_000L)
            return Sample(bytes,nowMs)
        val rate=(gained.toDouble()*1_000.0/elapsed).toLong()
        return if(rate>0L) Sample(bytes,nowMs,rate,nowMs)
            else Sample(bytes,nowMs)
    }
}
