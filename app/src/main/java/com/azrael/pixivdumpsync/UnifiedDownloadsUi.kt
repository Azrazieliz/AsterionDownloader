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
                val verifySelectedHoster=
                    com.asterion.videoflow.NetworkRoute.siteKey(item.url)=="mp4hentai.com" &&
                    item.error.orEmpty().contains("CAPTCHA",ignoreCase=true)
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
                                if(verifySelectedHoster){
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
                        State.FAILED->if(verifySelectedHoster)"VERIFY FILE HOST" else null
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
                        item.state=="RETRY" && state==State.HELD->"RETRY"
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
                    actionText=if(state==State.HELD)"RETRY" else null,
                    receivedBytes=item.bytes)
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
                rows+=Row(source,record.filename,info,state,null,
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
        return rows.filterNot{it.state in setOf(State.DONE,State.PAUSED) &&
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
            counts["Video"]=aggregate(found,
                setOf("done"),setOf("error","full_only_hold"),
                setOf("queued","retry_wait"),setOf("running"),setOf("paused"))
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
                    counts[source]=(counts[source]?:Totals())+contribution
                }
            }
        }
        return counts
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
                val result=runCatching{
                    val rows=collect(a)
                    Triple(rows,exactModuleTotals(a),
                        NduCloudQueue(a).use{it.status()})
                }
                a.runOnUiThread{
                    inFlight.set(false)
                    if(a.isFinishing || a.isDestroyed || surface.parent!==parent)
                        return@runOnUiThread
                    result.onSuccess{snapshot->
                        val(rows,totals,cloud)=snapshot
                        recoverIdleQueues(a,rows)
                        val next=LinearLayout(a).apply{
                            orientation=LinearLayout.VERTICAL
                        }
                        show(a,next,reload,rows,totals,cloud)
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
    /** A single module summary replaces repetitive per-file card matrices.
     * Item controls are available only after opening their own module.
     * Saved originals are excluded from bulk cancellation.
     */
    private fun show(a:Activity,parent:LinearLayout,refresh:()->Unit,
                     rows:List<Row>,exact:Map<String,Totals>,
                     cloud:NduCloudQueue.Status){
        val prefs=a.getSharedPreferences("asterion_downloads_ui",0)
        val expanded=prefs.getString("module",null)
        val groups=rows.groupBy{it.source}.entries.sortedWith(
            compareByDescending<Map.Entry<String,List<Row>>>{
                it.value.count{row->row.state==State.ACTIVE}
            }.thenByDescending{
                it.value.count{row->row.state==State.FAILED ||
                    row.state==State.HELD}
            }.thenBy{it.key.lowercase()})
        val selected=groups.map{(module,items)->
            exact[module]?:Totals(
                saved=items.count{it.state==State.DONE},
                failed=items.count{it.state in setOf(State.FAILED,State.HELD)},
                queued=items.count{it.state==State.QUEUED},
                active=items.count{it.state==State.ACTIVE},
                paused=items.count{it.state==State.PAUSED})
        }.fold(Totals()){acc,next->acc+next}
        parent.addView(ui.text(a,"Downloads",23f,ui.white,true),ui.lp(a,10))
        parent.addView(ui.text(a,
            "${selected.saved} saved · ${selected.failed} failed / needs action · "+
                "${selected.queued} queued · ${selected.active} active",
            12f,ui.secondary),ui.lp(a,6))
        parent.addView(ui.text(a,
            "TeraBox: ${cloud.completed} remotely verified · "+
                "${cloud.pending} awaiting cloud proof",
            12f,ui.secondary),ui.lp(a,4))
        if(!AppPower.enabled(a))parent.addView(
            ui.action(a,"RESUME TRANSFERS",true){
                AppPower.set(a,true);refresh()
            },ui.lp(a,7))
        if(groups.isEmpty()){
            parent.addView(ui.text(a,"No tracked downloads",13f,ui.secondary),
                ui.lp(a,12))
            return
        }
        for((source,items) in groups){
            val opened=source==expanded
            val totals=exact[source]?:Totals(
                saved=items.count{it.state==State.DONE},
                failed=items.count{it.state==State.FAILED ||
                    it.state==State.HELD},
                queued=items.count{it.state==State.QUEUED},
                active=items.count{it.state==State.ACTIVE},
                paused=items.count{it.state==State.PAUSED})
            val savedCount=totals.saved
            val failingCount=totals.failed
            val queuedCount=totals.queued
            val activeCount=totals.active
            val pausedCount=totals.paused
            val card=ui.card(a,10)
            val line=ui.row(a)
            val summary=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            summary.addView(ui.text(a,source,16f,ui.white,true))
            summary.addView(ui.text(a,
                (if(source in exact)"" else "Recent: ")+
                "$savedCount saved · $failingCount failed · $queuedCount queued"+
                    (if(activeCount>0)" · $activeCount active" else "")+
                    (if(pausedCount>0)" · $pausedCount paused" else ""),
                11f,ui.secondary),ui.lp(a,3))
            line.addView(summary,LinearLayout.LayoutParams(0,-2,1f))
            line.addView(ui.action(a,if(opened)"CLOSE" else "MANAGE",false){
                prefs.edit().putString("module",if(opened)null else source).apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{
                leftMargin=ui.px(a,8)
            })
            card.addView(line)
            if(opened){
                val pausable=items.filter{it.state in setOf(State.ACTIVE,State.QUEUED)
                    && it.actionText in setOf("PAUSE","HOLD")}
                val resumable=items.filter{it.state==State.PAUSED
                    && it.actionText=="RESUME"}
                val cancellable=items.filter{it.state!=State.DONE
                    && it.onRemove!=null}
                if(pausable.isNotEmpty()||resumable.isNotEmpty()||
                    cancellable.isNotEmpty()){
                    val commands=ui.row(a)
                    if(pausable.isNotEmpty())commands.addView(
                        ui.action(a,"PAUSE MODULE",false){
                            kotlin.concurrent.thread(name="asterion-pause-module"){
                                pausable.forEach{runCatching{it.action()}}
                                a.runOnUiThread{
                                    if(!a.isFinishing&&!a.isDestroyed)refresh()
                                }
                            }
                        },LinearLayout.LayoutParams(0,-2,1f))
                    if(resumable.isNotEmpty())commands.addView(
                        ui.action(a,"RESUME MODULE",true){
                            kotlin.concurrent.thread(name="asterion-resume-module"){
                                if(source=="Video"){
                                    VideoDb(a).use{db->resumable.forEach{row->
                                        row.key?.removePrefix("video:")?.toLongOrNull()
                                            ?.let{db.retry(it)}
                                    }}
                                    VideoEngine.launch(a.applicationContext,false)
                                }else resumable.forEach{runCatching{it.action()}}
                                a.runOnUiThread{
                                    if(!a.isFinishing&&!a.isDestroyed)refresh()
                                }
                            }
                        },LinearLayout.LayoutParams(0,-2,1f))
                    if(cancellable.isNotEmpty())commands.addView(
                        ui.action(a,"CANCEL QUEUE",false){
                            android.app.AlertDialog.Builder(a)
                                .setTitle("Cancel "+source+" unfinished downloads?")
                                .setMessage("Cancel "+cancellable.size+
                                    " tracked unfinished items in this module. "+
                                    "Saved originals will be preserved.")
                                .setNegativeButton("Keep",null)
                                .setPositiveButton("Cancel jobs"){_,_->
                                    kotlin.concurrent.thread(name="asterion-cancel-module"){
                                        cancellable.forEach{row->
                                            runCatching{row.onRemove?.invoke()}
                                        }
                                        a.runOnUiThread{
                                            if(!a.isFinishing&&!a.isDestroyed)refresh()
                                        }
                                    }
                                }.show()
                        },LinearLayout.LayoutParams(0,-2,1f))
                    card.addView(commands,ui.lp(a,10))
                }
                for(item in items.take(100)){
                    val detail=ui.row(a)
                    val desc=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
                    desc.addView(ui.text(a,item.filename,12f,ui.white).apply{
                        maxLines=1
                        ellipsize=android.text.TextUtils.TruncateAt.END
                    })
                    val label=when(item.state){
                        State.DONE->"Saved"
                        State.FAILED->"Failed"
                        State.HELD->"Needs action"
                        State.ACTIVE->if(item.receivedBytes>0L)
                            item.receivedBytes.toString()+" bytes received"
                            else "Connecting"
                        State.QUEUED->"Queued"
                        State.PAUSED->"Paused"
                    }
                    val issue=if(item.state in setOf(State.FAILED,State.HELD))
                        item.info.substringAfter(" · ",item.info).take(85)
                    else ""
                    desc.addView(ui.text(a,
                        label+(if(issue.isNotBlank())" · $issue" else ""),
                        11f,if(item.state==State.FAILED)ui.danger else ui.secondary)
                        .apply{
                            maxLines=2
                            ellipsize=android.text.TextUtils.TruncateAt.END
                        },ui.lp(a,2))
                    detail.addView(desc,LinearLayout.LayoutParams(0,-2,1f))
                    if(item.hasPrimaryAction){
                        val action=when(item.state){
                            State.DONE->"OPEN"
                            State.ACTIVE,State.QUEUED,State.PAUSED->item.actionText
                            State.FAILED->item.actionText?:"RETRY"
                            State.HELD->item.actionText?:"RETRY"
                        }
                        if(!action.isNullOrBlank() && action!="COOLDOWN")
                            detail.addView(ui.action(a,action,false){
                                item.action()
                                if(item.state!=State.DONE)refresh()
                            },LinearLayout.LayoutParams(-2,-2).apply{
                                leftMargin=ui.px(a,7)
                            })
                    }
                    card.addView(detail,ui.lp(a,10))
                }
            }
            parent.addView(card,ui.lp(a,7))
        }
    }
}
