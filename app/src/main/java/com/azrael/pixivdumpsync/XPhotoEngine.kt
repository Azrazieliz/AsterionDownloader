package com.azrael.pixivdumpsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** X/Twitter image archiver. Ignores videos; no media URL is routed to VideoFlow. */
object XPhotoEngine {
    private val working=AtomicBoolean(false)
    private val executor=Executors.newSingleThreadExecutor()
    private const val CHANNEL="asterion_x_images"
    private fun json(url:String):JSONObject {
        var last:Exception?=null
        repeat(3){attempt->
            try{
                val response=Jsoup.connect(url).ignoreContentType(true).ignoreHttpErrors(true)
                    .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36")
                    .header("Accept","application/json")
                    .timeout(30000).maxBodySize(12_000_000).execute()
                val value=runCatching{JSONObject(response.body())}.getOrNull()
                val status=response.statusCode()
                val code=value?.optInt("code",status)?:status
                if(status==404 && value?.optJSONArray("results")!=null)return value
                if(status in 200..299 && code in 200..299 &&
                    value!=null)return value
                val error=IllegalStateException("X metadata upstream HTTP $status / code $code")
                if(status !in setOf(408,429,500,502,503,504) &&
                    code !in setOf(429,500,502,503,504))throw error
                last=error
            }catch(t:Exception){
                last=t
                if(t is IllegalStateException &&
                    !t.message.orEmpty().contains("upstream HTTP"))throw t
            }
            if(attempt<2)Thread.sleep(750L*(attempt+1))
        }
        throw last?:IllegalStateException("X metadata service unavailable")
    }
    private fun enqueuePhotos(db:XPhotoQueue,source:String,entry:JSONObject):Int {
        val photos=entry.optJSONObject("media")?.optJSONArray("photos")?:return 0
        val id=entry.optString("id")
        var total=0
        for(i in 0 until photos.length()) {
            val photo=photos.optJSONObject(i)?:continue
            val url=photo.optString("url")
            if(!url.startsWith("https://"))continue
            if(db.addPhoto(source,id,url))total++
        }
        return total
    }
    private fun scan(context:Context,db:XPhotoQueue,source:String,handle:String,
                     initialCursor:String?,live:Boolean,backfill:Boolean,
                     historyComplete:Boolean){
        val path=runCatching{URI(source).path.orEmpty()}.getOrDefault("")
        val id=Regex("/status/([0-9]+)").find(path)?.groupValues?.get(1)
        if(id!=null){
            val root=json("https://api.fxtwitter.com/2/status/$id")
            val post=root.optJSONObject("status")?:root.optJSONObject("tweet")
                ?:error("Post metadata not returned")
            enqueuePhotos(db,source,post)
            return
        }
        val visited=HashSet<String>()
        fun getPage(cursor:String?):JSONObject{
            val target="https://api.fxtwitter.com/2/profile/$handle/media?count=100"+
                (if(cursor.isNullOrBlank())"" else
                    "&cursor="+URLEncoder.encode(cursor,"UTF-8"))
            // FxEmbed /media may return upstream HTTP 500 intermittently.
            // Its documented /statuses timeline has the same results/cursor format,
            // and enqueuePhotos filters entries to photos only.
            return try { json(target) } catch(mediaError:Exception) {
                val fallback="https://api.fxtwitter.com/2/profile/$handle/statuses?count=100"+
                    (if(cursor.isNullOrBlank())"" else
                        "&cursor="+URLEncoder.encode(cursor,"UTF-8"))
                try { json(fallback) } catch(other:Exception) {
                    throw IllegalStateException(
                        "X media and timeline both failed: "+mediaError.message+
                        " | "+other.message,other)
                }
            }
        }
        // Always examine the newest posts first. The saved cursor is exclusively
        // the older-content backfill position and must not suppress live sync.
        if(!live && (!backfill || historyComplete))return
        val latest=getPage(null)
        val newest=latest.optJSONArray("results")
        if(newest!=null){
            for(i in 0 until newest.length()){
                val post=newest.optJSONObject(i)?:continue
                enqueuePhotos(db,source,post)
            }
        }
        if(!backfill || historyComplete)return
        var cursor=initialCursor?:latest.optJSONObject("cursor")
            ?.optString("bottom")?.takeIf{it.isNotBlank()}
        if(cursor.isNullOrBlank()){
            db.cursor(source,handle,null)
            return
        }
        while(AppPower.enabled(context) &&
            !Thread.currentThread().isInterrupted){
            if(cursor.isNullOrBlank()||!visited.add(cursor))break
            val root=getPage(cursor)
            val list=root.optJSONArray("results")?:error("X media list missing")
            for(i in 0 until list.length()){
                val post=list.optJSONObject(i)?:continue
                enqueuePhotos(db,source,post)
            }
            val next=root.optJSONObject("cursor")?.optString("bottom")
                ?.takeIf{it.isNotBlank()}
            db.cursor(source,handle,next)
            cursor=next
        }
    }
    fun notice(context:Context,description:String):Notification {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(
                CHANNEL,"Asterion X photo downloads",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,
            Intent(context,AsterionHomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(context,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Asterion · X Images")
            .setContentText(description.take(100)).setContentIntent(open)
            .setOngoing(true).build()
    }
    fun start(context:Context,automatic:Boolean=false,targetSource:String?=null) {
        if(!AppPower.enabled(context))return
        val intent=Intent(context,XPhotoService::class.java)
            .putExtra("asterion_auto_sync",automatic)
            .putExtra("asterion_target_source",targetSource)
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)
        else context.startService(intent)
    }
    fun submit(context:Context,automatic:Boolean=false,targetSource:String?=null,
               onStatus:(String)->Unit={})=executor.submit{
        if(!AppPower.enabled(context))return@submit
        if(!working.compareAndSet(false,true))return@submit
        try{
            XPhotoQueue(context).use{db->
                db.recover()
                db.retryDueFailures()
                val watchers=db.allSources().filter{source->
                    source.enabled && (source.liveEnabled ||
                        (source.backfillEnabled && !source.historyComplete)) &&
                    (targetSource==null || source.url==targetSource)
                }
                val tasks=watchers.map{source->
                    BackfillAgents.submit(context,"xphotos:"+source.url){
                        if(AppPower.enabled(context)){
                            XPhotoQueue(context).use{worker->
                                onStatus("Scanning X @"+source.handle)
                                runCatching{scan(context,worker,source.url,source.handle,
                                    source.cursor,source.liveEnabled,
                                    source.backfillEnabled,source.historyComplete)}
                                    .onFailure{worker.markSourceError(source.url,
                                        it.message?:"X scan failed")}
                            }
                        }
                    }
                }
                for(task in tasks){
                    if(!AppPower.enabled(context))break
                    runCatching{task.get()}
                }
                while(AppPower.enabled(context)){
                    val photo=db.next()?:break
                    db.change(photo.id,"running")
                    try{
                        val saved=fetchPhoto(context,photo.photo)
                        db.change(photo.id,"done",saved)
                        if(NduSettings.configured(context)){
                            NduCloudQueue(context).use{it.enqueue("x",
                                Uri.parse(saved).lastPathSegment.orEmpty().ifBlank{"x_"+photo.id+".jpg"},
                                Uri.parse(saved))}
                            NduUploadService.launch(context)
                        }
                        onStatus("Saved X image")
                    }catch(t:Exception){
                        db.change(photo.id,"error",error=t.message)
                        onStatus("X image failed")
                    }
                }
            }
        }finally{working.set(false)}
    }
    private fun fetchPhoto(context:Context,source:String):String {
        val remote=URI(source)
        require(remote.scheme=="https" && remote.host=="pbs.twimg.com" &&
            remote.path.startsWith("/media/")){"Only X original photo CDN links can be downloaded"}
        val address=Uri.parse(source)
        val fileBase=address.lastPathSegment.orEmpty()
            .substringBefore('?').substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"),"_")
            .take(90).ifBlank{"x_photo"}
        val original=address.getQueryParameter("format")
        val ext=original?.takeIf{it in listOf("jpg","jpeg","png","webp")}
            ?:address.lastPathSegment.orEmpty().substringAfterLast('.',"jpg")
                .lowercase().takeIf{it in listOf("jpg","jpeg","png","webp")}?: "jpg"
        var last:Exception?=null
        repeat(4){attempt->
            try{
                val preferred=if(attempt>=2 && source.contains("name=orig"))
                    source.replace("name=orig","name=large") else source
                val connection=java.net.URL(preferred).openConnection().apply{
                    connectTimeout=30000;readTimeout=45000
                    setRequestProperty("User-Agent","Mozilla/5.0")
                }
                val mime=connection.contentType.orEmpty().substringBefore(';').trim()
                require(mime.startsWith("image/")){"X server returned $mime"}
                val values=ContentValues().apply{
                    put(MediaStore.Downloads.DISPLAY_NAME,"$fileBase.$ext")
                    put(MediaStore.Downloads.MIME_TYPE,mime)
                    put(MediaStore.Downloads.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS+"/AsterionDownloader/X/")
                    put(MediaStore.Downloads.IS_PENDING,1)
                }
                val dest=context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                    ?:error("Cannot create X image")
                try{
                    var bytes=0L
                    connection.getInputStream().buffered().use{input->
                        context.contentResolver.openOutputStream(dest,"w")!!.use{output->
                            val buffer=ByteArray(65536)
                            while(true){val n=input.read(buffer);if(n<0)break
                                output.write(buffer,0,n);bytes+=n}
                        }
                    }
                    require(bytes>128 && (connection.contentLengthLong<=0 ||
                        bytes==connection.contentLengthLong)){"X image truncated"}
                    context.contentResolver.update(dest,ContentValues().apply{
                        put(MediaStore.Downloads.IS_PENDING,0)},null,null)
                    val canonical=CrossSourceDedup(context).use{
                        it.registerFile(context,dest)
                    }
                    RounderDispatch.saved(context,canonical,"$fileBase.$ext","X")
                    return canonical.toString()
                }catch(t:Exception){
                    context.contentResolver.delete(dest,null,null);throw t
                }
            }catch(t:Exception){
                last=t;if(attempt<3)Thread.sleep(1000L*(attempt+1))
            }
        }
        throw last?:IllegalStateException("X photo download failed")
    }
}
class XPhotoService:Service(){
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        startForeground(25823,XPhotoEngine.notice(this,"Checking images"))
        val automatic=intent?.getBooleanExtra("asterion_auto_sync",false)==true
        val target=intent?.getStringExtra("asterion_target_source")
        val future=XPhotoEngine.submit(applicationContext,automatic,target){message->
            getSystemService(NotificationManager::class.java)
                .notify(25823,XPhotoEngine.notice(this,message))
        }
        Thread{runCatching{future.get()};stopSelfResult(startId)}.start()
        return START_REDELIVER_INTENT
    }
}
