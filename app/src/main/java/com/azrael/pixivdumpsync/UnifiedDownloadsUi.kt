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
                    receivedBytes=item.bytes,key="android:"+item.job.id)
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
        // A child inserted into an already-attached Activity can receive
        // onViewAttachedToWindow BEFORE the listener is registered. Always
        // arm an initial tick after insertion. A generation token prevents
        // duplicate polling on detach/reattach and stops offscreen work.
        val pollGeneration=java.util.concurrent.atomic.AtomicLong(0L)
        fun poll(token:Long){
            if(token!=pollGeneration.get() || a.isFinishing || a.isDestroyed ||
                surface.parent!==parent || !surface.isAttachedToWindow)return
            reload()
            surface.postDelayed({poll(token)},VISIBLE_REFRESH_MS)
        }
        fun armPoll(){
            val token=pollGeneration.incrementAndGet()
            surface.postDelayed({poll(token)},VISIBLE_REFRESH_MS)
        }
        surface.addOnAttachStateChangeListener(object:View.OnAttachStateChangeListener{
            override fun onViewAttachedToWindow(v:View){armPoll()}
            override fun onViewDetachedFromWindow(v:View){
                pollGeneration.incrementAndGet()
            }
        })
        // Works whether or not Android already delivered the attach event.
        surface.post{
            if(pollGeneration.get()==0L && surface.isAttachedToWindow &&
                surface.parent===parent)armPoll()
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
        String,Pair<Long,Long>>()

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
        if(item.receivedBytes<=0L)return null
        if(liveSamples.size>80)liveSamples.clear()
        val now=android.os.SystemClock.elapsedRealtime()
        val previous=liveSamples.put(rowId(item),item.receivedBytes to now)
            ?:return null
        val elapsed=now-previous.second
        val added=item.receivedBytes-previous.first
        if(elapsed<600L || elapsed>30_000L || added<=0L)return null
        return sizeLabel((added.toDouble()*1000.0/elapsed).toLong())+"/s"
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
        val all=sources.map{totalsFor(it)}
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
        val live=rows.filter{
            DownloadOverviewPolicy.isLiveFile(
                it.state.name,it.source,it.filename,it.mime)
        }.sortedByDescending{it.updated}
        val liveHeader=ui.row(a)
        liveHeader.addView(ui.text(a,"Live transfers",16f,ui.white,true),
            LinearLayout.LayoutParams(0,-2,1f))
        liveHeader.addView(ui.text(a,live.size.toString()+" active",
            11f,ui.secondary),LinearLayout.LayoutParams(-2,-2))
        parent.addView(liveHeader,ui.lp(a,16,8))
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
                val name=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                }
                name.addView(ui.text(a,item.source,11f,ui.secondary,true))
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
                // Progress reflects the actual worker report. For unmeasured
                // media, an indeterminate bar is more honest than fake 0%.
                // VideoDb.progress is extractor/stage percentage, NOT
                // received bytes / verified expected length. Do not render
                // it as a file progress percentage or invent an ETA.
                val validProgress=if(item.source=="Video")null
                    else item.percent?.coerceIn(0,100)
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
                val speed=speedLabel(item)
                val details=ArrayList<String>()
                if(validProgress!=null)
                    details.add(validProgress.toString()+"% reported")
                if(item.receivedBytes>0L)
                    details.add(sizeLabel(item.receivedBytes)+" received")
                if(speed!=null)details.add(speed)
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
                // No per-file history/matrix in this view. Retain scoped
                // commands that operate on the real durable queue records.
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
                if(pausable.isNotEmpty() || resumable.isNotEmpty() ||
                    cancellable.isNotEmpty()){
                    val actions=ui.row(a)
                    if(pausable.isNotEmpty())
                        actions.addView(ui.action(a,"PAUSE",false){
                            // Callbacks are UI-facing (some launch Activities
                            // or notifications). Never invoke them from an
                            // unsafe raw worker thread.
                            applyModuleActions(a,pausable.map{it.action},refresh)
                        },LinearLayout.LayoutParams(0,-2,1f))
                    if(resumable.isNotEmpty())
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
                val failures=items.filter{
                    it.state in setOf(State.FAILED,State.HELD)
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
