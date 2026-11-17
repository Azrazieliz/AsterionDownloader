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
    private val lastQueueRecovery=java.util.concurrent.atomic.AtomicLong(0L)
    private const val RECOVERY_INTERVAL_MS=180_000L
    private const val VISIBLE_REFRESH_MS=3_000L
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
        if(!AppPower.enabled(a) || rows.any{it.state==State.ACTIVE})return
        val queued=rows.filter{it.state==State.QUEUED}.map{it.source}.toSet()
        if(queued.isEmpty())return
        val now=android.os.SystemClock.elapsedRealtime()
        val previous=lastQueueRecovery.get()
        if(previous>0 && now-previous<RECOVERY_INTERVAL_MS)return
        if(!lastQueueRecovery.compareAndSet(previous,now))return
        kotlin.concurrent.thread(name="asterion-queued-worker-rearm"){
            val app=a.applicationContext
            if("Video" in queued){
                runCatching{VideoEngine.launch(app,false)}
            }
            if("X" in queued)runCatching{XPhotoEngine.start(app)}
            if("Comic ZIP" in queued)runCatching{ChapterArchives.start(app)}
            if("EPUB" in queued || "Novel ZIP" in queued)
                runCatching{NovelEpubService.schedule(app)}
            if(queued.any{it !in setOf("Video","X","Comic ZIP","EPUB","Novel ZIP")})
                runCatching{DirectDownloadRetry.ensure(app)}
        }
    }
    private enum class State { ACTIVE, QUEUED, PAUSED, HELD, FAILED, DONE }
    private enum class Filter(val label:String) {
        ALL("All"),ACTIVE("Transferring"),QUEUED("Queued"),PAUSED("Paused"),
        HELD("Needs action"),FAILED("Failed"),DONE("Saved")
    }
    private data class Row(
        val source:String,val filename:String,val info:String,val state:State,
        val percent:Int?,val updated:Long,val action:()->Unit,
        val saved:String?=null,val image:String?=null,val mime:String="*/*",
        val actionText:String?=null,
        val receivedBytes:Long=0L,
        val onRemove:(()->Unit)?=null,
        val hasPrimaryAction:Boolean=true,
        val key:String?=null
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
                    it.statSize!=0L
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
                val state=when{
                    item.status=="done" && present->State.DONE
                    item.status=="done" || item.status=="error"->State.FAILED
                    item.status in setOf("full_only_hold","retry_wait")->State.HELD
                    item.status=="paused"->State.PAUSED
                    item.status=="running"->State.ACTIVE
                    else->State.QUEUED
                }
                val retired=ProviderRetirement.isRetired(item.url)
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
                            "Receiving original · "+received+" · engine "+item.progress+"%"
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
                    info,state,if(item.status=="running" && item.progress>0)
                        item.progress else null,item.updated,{
                        if(!retired || state==State.DONE)when(state){
                            State.DONE->item.savedUri?.let{open(a,it,"video/*")}
                            State.FAILED,State.HELD->{
                                VideoDb(a).use{it.retry(item.id)}
                                VideoEngine.launch(a,false)
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
                        State.HELD->"RETRY"
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
                        VideoEngine.cancelVideo(a.applicationContext,item.id)
                    }),hasPrimaryAction=!retired || state==State.DONE,
                    key="video:"+item.id)
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
                        else->null
                    },
                    onRemove=if(state!=State.ACTIVE)({
                        if(state==State.DONE)hideRow(a,Row(
                            "Comic ZIP",item.title,"",state,null,0L,{},
                            key="comic:"+item.id))
                        else ChapterQueue(a).use{it.dismiss(item.id)}
                    }) else null,key="comic:"+item.id)
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
                        else->{}
                    }
                },if(present)item.savedUri else null,mime=mime,
                    actionText=if(providerCooling)"COOLDOWN"
                        else if(item.state=="RETRY" && state==State.HELD)"RETRY"
                        else null,
                    onRemove=if(state!=State.ACTIVE)({
                        if(state==State.DONE)hideRow(a,Row(type,name,"",state,
                            null,0L,{},key="novel:"+item.url))
                        else NovelEpubQueue(a).use{it.dismiss(item.url)}
                    }) else null,key="novel:"+item.url)
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
                        else->null
                    },
                    onRemove=if(state!=State.ACTIVE)({
                        if(state==State.DONE)hideRow(a,Row(
                            "X",name,"",state,null,0L,{},key="x:"+item.id))
                        else XPhotoQueue(a).use{it.dismiss(item.id)}
                    }) else null,key="x:"+item.id)
            }
        }
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
            val receipt=if(item.status==DownloadManager.STATUS_SUCCESSFUL && present)
                OriginalFileReceipt.verify(a,dm,item.job.id,
                    item.job.filename,item.total)
                else null
            if(receipt!=null){
                val next=if(receipt.valid)"VERIFIED" else "INVALID"
                if(OriginalReceiptState.get(a,item.job.id)!=next)
                    OriginalReceiptState.set(a,item.job.id,next)
            }
            val state=when {
                item.status==DownloadManager.STATUS_FAILED -> State.FAILED
                item.status==DownloadManager.STATUS_SUCCESSFUL &&
                    receipt?.valid==true -> State.DONE
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
                        receipt!=null && !receipt.valid ->
                            "Rejected invalid original · "+receipt.reason.take(100)
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
                    actionText=if(state==State.HELD)"RETRY" else null,
                    receivedBytes=item.bytes)
        }
        val hidden=hiddenPrefs(a).getStringSet("keys",emptySet()).orEmpty()
        return rows.filterNot{it.state in setOf(State.DONE,State.PAUSED) &&
            rowId(it) in hidden}.sortedWith(compareBy<Row>{
            when(it.state){
                State.ACTIVE->0;State.FAILED->1;State.HELD->2
                State.QUEUED->3;State.PAUSED->4;State.DONE->5
            }
        }.thenByDescending{it.updated})
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
        lateinit var reload:()->Unit
        reload=reload@{
            if(a.isFinishing || a.isDestroyed || surface.parent!==parent ||
                !inFlight.compareAndSet(false,true))return@reload
            kotlin.concurrent.thread(name="asterion-downloads-read"){
                // A visual refresh is read-only: it must not reconcile or
                // restart queue agents merely because a user taps Refresh.
                val result=runCatching{collect(a)}
                a.runOnUiThread{
                    inFlight.set(false)
                    if(a.isFinishing || a.isDestroyed || surface.parent!==parent)
                        return@runOnUiThread
                    result.onSuccess{rows->
                        recoverIdleQueues(a,rows)
                        val next=LinearLayout(a).apply{
                            orientation=LinearLayout.VERTICAL
                        }
                        show(a,next,reload,rows)
                        // Replace only this module's view, in the same frame.
                        // This avoids blanking the whole Activity/scroller.
                        surface.removeAllViews()
                        surface.addView(next)
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
        // In-place polling tracks actual SQLite/MediaStore state; no user
        // "Refresh" press is required, and leaving Downloads stops it.
        fun poll(){
            if(a.isFinishing||a.isDestroyed||surface.parent!==parent ||
                !surface.isAttachedToWindow)return
            reload()
            surface.postDelayed({poll()},VISIBLE_REFRESH_MS)
        }
        surface.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){
                surface.postDelayed({poll()},VISIBLE_REFRESH_MS)
            }
            override fun onViewDetachedFromWindow(v:View){}
        })
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
    private fun show(a:Activity,parent:LinearLayout,refresh:()->Unit,rows:List<Row>){
        val active=rows.count{it.state==State.ACTIVE}
        val transferring=rows.count{
            DownloadProgressPolicy.receiving(
                it.state==State.ACTIVE,it.receivedBytes,it.percent)
        }
        val preparing=active-transferring
        val queued=rows.count{it.state==State.QUEUED}
        val paused=rows.count{it.state==State.PAUSED}
        val attention=rows.count{it.state==State.HELD}
        val failed=rows.count{it.state==State.FAILED}
        val saved=rows.count{it.state==State.DONE}
        val heading=ui.text(a,"Downloads",24f,ui.white,true)
        parent.addView(heading,ui.lp(a,15))
        // The visible module now refreshes itself every 3 seconds in place.
        if(!AppPower.enabled(a)){
            val notice=ui.card(a,12)
            notice.addView(ui.text(a,"Transfers paused",15f,ui.danger,true))
            notice.addView(ui.text(a,
                "Existing jobs stay queued until you resume. Nothing has been discarded.",
                12f,ui.secondary),ui.lp(a,5))
            notice.addView(ui.action(a,"Resume transfers",true){
                AppPower.set(a,true)
                val started=VideoEngine.launch(a,false)
                if(!started)VideoEngine.submit(a,false)
                XPhotoEngine.start(a)
                ChapterArchives.start(a)
                NovelEpubService.schedule(a)
                // Discovery's SQLite scan is not safe to run in a touch
                // callback. Launch both durable queues off the main thread.
                kotlin.concurrent.thread(name="asterion-start-original-queues"){
                    ModuleOriginalAgents.startAll(a.applicationContext)
                    DirectDownloadRetry.retry(a.applicationContext)
                }
                refresh()
            },ui.lp(a,7))
            parent.addView(notice,ui.lp(a,9))
        }
        parent.addView(ui.text(a,
            "$transferring progressing · $preparing connecting · $queued queued · $paused paused · "+
                "$attention need action · $failed failed · $saved saved (recent)",
            12f,if(failed>0)ui.danger else ui.secondary),ui.lp(a,6))
        // Queued jobs are rearmed by their own persisted workers on opening
        // this screen. A manual Start button was hiding scheduler failures.
        val refreshButton=ui.row(a)
        refreshButton.addView(ui.action(a,"Refresh now",false){refresh()},
            LinearLayout.LayoutParams(-1,-2))
        parent.addView(refreshButton,ui.lp(a,11))
        // Clearing is explicit and affects only unfinished video tasks.
        // Saved MediaStore files and non-video queues are never touched.
        if(rows.any{it.source=="Video" && it.state!=State.DONE}){
            parent.addView(ui.action(a,"Clear unfinished videos",false){
                android.app.AlertDialog.Builder(a)
                    .setTitle("Clear unfinished video downloads?")
                    .setMessage("Stop active video transfers and remove all "+
                        "queued, failed, paused and retrying video rows from "+
                        "the list. Saved files remain untouched. Re-add a "+
                        "video manually if you want it again.")
                    .setNegativeButton("Keep",null)
                    .setPositiveButton("Clear"){_,_->
                        kotlin.concurrent.thread(name="asterion-clear-video-queue"){
                            val removed=VideoEngine.clearUnfinished(a.applicationContext)
                            a.runOnUiThread{
                                Toast.makeText(a,"Cleared $removed unfinished videos",
                                    Toast.LENGTH_LONG).show()
                                refresh()
                            }
                        }
                    }.show()
            },ui.lp(a,7))
        }
        parent.addView(ui.action(a,"Torrent transfers",false){
            TorrentTransfers.showQueue(a)
        },ui.lp(a,5))
        val pref=a.getSharedPreferences("asterion_downloads_ui",0)
        val filter=runCatching{
            Filter.valueOf(pref.getString("filter","ALL").orEmpty())
        }.getOrDefault(Filter.ALL)
        val chips=ui.row(a)
        for(option in Filter.entries){
            chips.addView(ui.chip(a,option.label,option==filter){
                pref.edit().putString("filter",option.name).putInt("page",20).apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{rightMargin=ui.px(a,7)})
        }
        parent.addView(HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false
            addView(chips)
        },ui.lp(a,13))
        val filtered=rows.filter{
            filter==Filter.ALL || it.state.name==filter.name
        }
        val size=pref.getInt("page",20).coerceIn(10,160)
        for(item in filtered.take(size)){
            val card=ui.row(a).apply{
                background=ui.touch(a,ui.surface,16)
                setPadding(ui.px(a,11),ui.px(a,11),ui.px(a,11),ui.px(a,11))
            }
            val image=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                background=ui.shape(a,ui.raised,12)
                setPadding(ui.px(a,12),ui.px(a,12),ui.px(a,12),ui.px(a,12))
                setImageResource(when(item.mime){
                    "video/*"->R.drawable.ic_source_video
                    "application/zip","application/epub+zip"->R.drawable.ic_source_toonily
                    else->R.drawable.ic_source_site
                })
                contentDescription="File preview: "+item.filename
            }
            card.addView(image,LinearLayout.LayoutParams(ui.px(a,60),ui.px(a,60))
                .apply{rightMargin=ui.px(a,11)})
            thumbnail(a,image,item)
            val main=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            main.addView(ui.text(a,item.filename.ifBlank{"Unnamed file"},14f,ui.white,true).apply{
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            })
            val label=when(item.state){
                State.DONE->"Saved"
                State.FAILED->"Failed"
                State.HELD->"Retry available"
                State.PAUSED->"Paused"
                State.QUEUED->"Queued"
                State.ACTIVE->when {
                    item.receivedBytes>0L && item.percent!=null && item.percent>0->
                        "Transferring · ~"+item.percent+"% (media/fragment progress)"
                    item.receivedBytes>0L->"Receiving original bytes"
                    item.percent!=null && item.percent>0->
                        "Engine progress · "+item.percent+"%"
                    else->"Preparing / connecting"
                }
            }
            val stateColor=when(item.state){
                State.FAILED->ui.danger
                State.ACTIVE->android.graphics.Color.rgb(104,214,164)
                State.PAUSED->ui.secondary
                State.DONE->ui.primary
                State.HELD->android.graphics.Color.rgb(242,194,116)
                State.QUEUED->ui.secondary
            }
            main.addView(ui.text(a,label,12f,stateColor,true),ui.lp(a,4))
            main.addView(ui.text(a,item.info.take(110),11f,ui.secondary).apply{
                maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
            },ui.lp(a,3))
            if(item.state==State.ACTIVE && item.percent!=null &&
                item.percent>0){
                main.addView(ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply{
                    max=100;progress=item.percent
                    progressTintList=ColorStateList.valueOf(
                        if(item.receivedBytes>0L)
                            android.graphics.Color.rgb(104,214,164)
                        else ui.secondary)
                },LinearLayout.LayoutParams(-1,ui.px(a,3)).apply{topMargin=ui.px(a,7)})
            }
            card.addView(main,LinearLayout.LayoutParams(0,-2,1f))
            // Keep per-file Retry and Clear together IN that file's card.
            // The old parent-level Clear rendered as a huge detached button
            // immediately above the next unrelated download row.
            val actions=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            if(item.hasPrimaryAction && (item.state in setOf(
                    State.DONE,State.FAILED,State.HELD,State.PAUSED) ||
                    item.actionText!=null)){
                val command=when(item.state){
                    State.DONE->"OPEN";State.FAILED->item.actionText?:"RETRY"
                    State.HELD->item.actionText?:"SIGN IN"
                    State.ACTIVE,State.QUEUED,State.PAUSED->item.actionText.orEmpty()
                }
                val action=ui.action(a,command,false){
                    item.action()
                    if(item.state!=State.DONE)refresh()
                }
                // Pausing is a neutral stop; resuming is a positive action.
                // Red is reserved exclusively for actual failed transfers.
                val actionColor=when(command){
                    "RESUME","START"->android.graphics.Color.rgb(38,105,79)
                    "RETRY"->android.graphics.Color.rgb(113,83,39)
                    "PAUSE","HOLD"->ui.raised
                    else->ui.raised
                }
                action.background=ui.touch(a,actionColor,15,
                    if(command=="RESUME")android.graphics.Color.rgb(92,182,139)
                    else ui.border)
                action.setTextColor(if(command=="RESUME")
                    android.graphics.Color.rgb(226,255,239) else ui.white)
                action.isSingleLine=true
                action.setPadding(ui.px(a,5),0,ui.px(a,5),0)
                actions.addView(action,LinearLayout.LayoutParams(-1,-2).apply{
                    bottomMargin=ui.px(a,5)
                })
            }
            // Each video has independent cancellation even when its primary
            // control is PAUSE, RETRY or HOLD. Do not rely on a global stop.
            val removal=item.onRemove ?: if(item.state in setOf(
                    State.DONE,State.PAUSED))({
                    hideRow(a,item)
                }) else null
            removal?.let{remove->
                val label=when{
                    item.state==State.ACTIVE->"CANCEL"
                    else->"CLEAR"
                }
                val removeButton=ui.action(a,label,false){
                    remove()
                    refresh()
                }.apply{
                    isSingleLine=true
                    setTextColor(ui.secondary)
                }
                actions.addView(removeButton,LinearLayout.LayoutParams(-1,-2))
            }
            if(actions.childCount>0)
                card.addView(actions,LinearLayout.LayoutParams(ui.px(a,88),-2).apply{
                    leftMargin=ui.px(a,6)
                })
            parent.addView(card,ui.lp(a,8))
        }
        if(filtered.size>size)parent.addView(ui.action(a,
            "Show more ("+(filtered.size-size)+")",false){
            pref.edit().putInt("page",(size+20).coerceAtMost(160)).apply()
            refresh()
        },ui.lp(a,10))
        if(filtered.isEmpty())parent.addView(ui.text(a,
            if(rows.isEmpty())"No downloads yet." else "Nothing in this filter.",
            13f,ui.secondary),ui.lp(a,13))
    }
}
