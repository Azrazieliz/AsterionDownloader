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
        val onRemove:(()->Unit)?=null
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
        // An Android native crash/ANR or OOM may never reach the application's
        // Java uncaught handler. Surface the OS's real last-exit diagnostic
        // instead of requiring another speculative APK.
        CrashDiagnostics.safeForClipboard(a)?.let{report->
            val prefs=a.getSharedPreferences("asterion_crash_reviewed",0)
            val key=report.hashCode()
            if(prefs.getInt("last_copied",Int.MIN_VALUE)!=key){
                val title=report.lineSequence().firstOrNull()
                    .orEmpty().take(160)
                rows+=Row("Diagnostics","Previous Android process failure",
                    title.ifBlank{"Android crash, ANR or memory exit was recorded"},
                    State.HELD,null,System.currentTimeMillis(),{
                    val clipboard=a.getSystemService(
                        android.content.ClipboardManager::class.java)
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                        "Asterion Downloader crash report",report))
                    prefs.edit().putInt("last_copied",key).apply()
                    Toast.makeText(a,"Crash report copied; saved files unchanged",
                        Toast.LENGTH_LONG).show()
                },actionText="COPY CRASH REPORT")
            }
        }
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
                val journal=if(item.status=="running")
                    VideoTransferJournal.current(a,item.id) else null
                val bytes=if(journal?.stage=="receiving")
                    journal.detail.toLongOrNull()?.coerceAtLeast(0L)?:0L
                    else 0L
                val received=if(bytes>0)
                    String.format(java.util.Locale.US,"%.1f MiB",
                        bytes.toDouble()/1_048_576.0) else ""
                val state=when{
                    item.status=="done" && present->State.DONE
                    item.status=="done" || item.status=="error"->State.FAILED
                    item.status in setOf("full_only_hold","retry_wait")->State.HELD
                    item.status=="paused"->State.PAUSED
                    item.status=="running"->State.ACTIVE
                    else->State.QUEUED
                }
                val isArchivebateSelected=runCatching{
                    val source=java.net.URI(item.url)
                    source.scheme=="https" &&
                        source.host.orEmpty().lowercase().removePrefix("www.")==
                            "archivebate.com" &&
                        Regex("""^/watch/[0-9]{1,16}/?$""")
                            .matches(source.path.orEmpty())
                }.getOrDefault(false)
                val info=when(state){
                    State.DONE->"Video · Saved on device"
                    State.FAILED->"Video · "+(item.error?:"Saved file is missing")
                    State.HELD->if(item.status=="retry_wait")
                        "Video · Retry scheduled: "+item.error.orEmpty().take(110)
                        else "Video · "+item.error.orEmpty()
                            .ifBlank{"Previous source verification was inconclusive; manual retry available"}
                            .take(110)
                    State.PAUSED->"Video · Paused by user; original and progress retained"
                    State.QUEUED->"Video · Waiting for transfer worker"
                    State.ACTIVE->"Video · "+if(bytes>0)"Receiving original · "+received
                        else if(journal?.stage=="transfer")"Connecting to original"
                        else "Inspecting / preparing original"
                }
                rows+=Row("Video",fileName(a,if(present)item.savedUri else null,item.title),
                    info,state,if(item.status=="running")item.progress else null,item.updated,{
                        when(state){
                            State.DONE->item.savedUri?.let{open(a,it,"video/*")}
                            State.FAILED->{
                                if(isArchivebateSelected)
                                    a.startActivity(Intent(a,ArchivebateWatchActivity::class.java)
                                        .putExtra(ArchivebateWatchActivity.WATCH_URL,item.url)
                                        .putExtra(ArchivebateWatchActivity.VIDEO_TITLE,item.title))
                                else{
                                    VideoDb(a).use{it.retry(item.id)}
                                    VideoEngine.launch(a,false)
                                }
                            }
                            State.ACTIVE,State.QUEUED->VideoEngine.pauseVideo(a,item.id)
                            State.PAUSED->VideoEngine.resumeVideo(a,item.id)
                            State.HELD->{
                                if(isArchivebateSelected)
                                    a.startActivity(Intent(a,ArchivebateWatchActivity::class.java)
                                        .putExtra(ArchivebateWatchActivity.WATCH_URL,item.url)
                                        .putExtra(ArchivebateWatchActivity.VIDEO_TITLE,item.title))
                                else{
                                    VideoDb(a).use{it.retry(item.id)}
                                    VideoEngine.launch(a,false)
                                }
                            }
                            else->{}
                        }
                    },if(present)item.savedUri else null,mime="video/*",
                    receivedBytes=bytes,
                    actionText=when(state){
                        State.ACTIVE->"PAUSE"
                        State.QUEUED->"HOLD"
                        State.PAUSED->"RESUME"
                        State.HELD->if(isArchivebateSelected)"OPEN PLAYER" else "RETRY"
                        State.FAILED->if(isArchivebateSelected)"OPEN PLAYER" else null
                        else->null
                    },
                    onRemove=if(state==State.DONE)null else ({
                        VideoEngine.cancelVideo(a.applicationContext,item.id)
                    }))
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
                    })
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
                    item.state=="RETRY" && item.error.orEmpty().startsWith(
                        "Chapter extraction continuing:")->State.QUEUED
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
                        else if(item.state=="RETRY")"RETRY" else null)
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
                    })
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
        return rows.sortedWith(compareBy<Row>{
            when(it.state){
                State.ACTIVE->0;State.FAILED->1;State.HELD->2
                State.QUEUED->3;State.PAUSED->4;State.DONE->5
            }
        }.thenByDescending{it.updated})
    }
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val loading=ui.text(a,"Loading downloads…",13f,ui.secondary)
        parent.addView(loading,ui.lp(a,8))
        kotlin.concurrent.thread(name="asterion-downloads-read"){
            val app=a.applicationContext
            val cleared=runCatching{DirectDownloadRetry.reconcileStalled(app)}
                .getOrDefault(0)
            if(cleared>0)ModuleOriginalAgents.startAll(app)
            val result=runCatching{collect(a)}
            a.runOnUiThread {
                if(a.isFinishing || a.isDestroyed || loading.parent!==parent)
                    return@runOnUiThread
                parent.removeView(loading)
                result.onSuccess{show(a,parent,refresh,it)}
                    .onFailure{parent.addView(ui.text(a,
                        "Unable to load downloads: "+it.message,13f,ui.danger))}
            }
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
        // Visible, real queue updates without a fake progress animation.
        // This redraws only while jobs genuinely have work to report, and
        // stops automatically when the user leaves this screen.
        if(rows.any{it.state==State.ACTIVE || it.state==State.QUEUED}){
            parent.postDelayed({
                if(!a.isFinishing && !a.isDestroyed &&
                    heading.parent===parent && heading.isAttachedToWindow)
                    refresh()
            },5_000L)
        }
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
        val shortcuts=ui.row(a)
        shortcuts.addView(ui.action(a,"Start queued transfers",true){
            if(!AppPower.enabled(a)){
                Toast.makeText(a,"Resume transfers first",Toast.LENGTH_LONG).show()
            }else{
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
                Toast.makeText(a,"Transfer workers requested; progress appears here",
                    Toast.LENGTH_LONG).show()
            }
            refresh()
        },LinearLayout.LayoutParams(0,-2,1f))
        shortcuts.addView(ui.action(a,"Refresh",false){refresh()},
            LinearLayout.LayoutParams(0,-2,0.6f).apply{
                leftMargin=ui.px(a,8)
            })
        parent.addView(shortcuts,ui.lp(a,12))
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
                State.ACTIVE->if(item.percent!=null && item.percent>0)
                    "Transferring · "+item.percent+"%"
                    else "Preparing / connecting"
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
            if(item.state==State.ACTIVE && item.percent!=null){
                main.addView(ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal).apply{
                    max=100;progress=item.percent
                    progressTintList=ColorStateList.valueOf(
                        android.graphics.Color.rgb(104,214,164))
                },LinearLayout.LayoutParams(-1,ui.px(a,3)).apply{topMargin=ui.px(a,7)})
            }
            card.addView(main,LinearLayout.LayoutParams(0,-2,1f))
            // Keep per-file Retry and Clear together IN that file's card.
            // The old parent-level Clear rendered as a huge detached button
            // immediately above the next unrelated download row.
            val actions=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            if(item.state in setOf(State.DONE,State.FAILED,State.HELD,
                    State.PAUSED) || item.actionText!=null){
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
            item.onRemove?.let{remove->
                val label=if(item.state==State.ACTIVE)"CANCEL" else "CLEAR"
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
