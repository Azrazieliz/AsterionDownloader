package com.azrael.pixivdumpsync

import android.app.*
import android.content.*
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Each page is cached on disk before ZIP assembly; never marks partial ZIP as complete. */
object ChapterArchives {
    private val executor=Executors.newSingleThreadExecutor()
    private val busy=AtomicBoolean(false)
    private const val CHANNEL="asterion_chapter_transfers"
    fun start(context:Context){
        if(!AppPower.enabled(context))return
        val intent=Intent(context,ChapterArchiveService::class.java)
        if(Build.VERSION.SDK_INT>=26)context.startForegroundService(intent)
        else context.startService(intent)
    }
    fun notification(context:Context,message:String):Notification{
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"Chapter downloads",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(context,0,
            Intent(context,DownloaderHubActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context,CHANNEL)
            .setContentTitle("Asterion Downloader · Chapters")
            .setContentText(message.take(95))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open).setOngoing(true).build()
    }
    fun run(context:Context,onStatus:(String)->Unit)=executor.submit{
        if(!AppPower.enabled(context))return@submit
        if(!busy.compareAndSet(false,true))return@submit
        try{
            ChapterQueue(context).use{db->
                db.recover()
                db.retryDueFailures()
                while(AppPower.enabled(context)){
                    val job=db.next()?:break
                    db.attempt(job.id)
                    db.update(job.id,"working",job.progress)
                    try{
                        val uri=build(context,job){pct->
                            db.update(job.id,"working",pct)
                            onStatus("${job.title}: $pct%")
                        }
                        db.update(job.id,"done",100,savedUri=uri)
                        if(NduSettings.configured(context)) {
                            NduCloudQueue(context).use { queue->
                                queue.enqueue("comics",safeName(job.title)+".zip",Uri.parse(uri))
                            }
                            NduUploadService.launch(context)
                        }
                        onStatus("Saved ${job.title}")
                    }catch(t:Exception){
                        db.update(job.id,"error",job.progress,t.message?:"Unknown failure")
                        onStatus("Failed ${job.title}")
                    }
                }
            }
        }finally{busy.set(false)}
    }
    private fun safeName(value:String):String=value.replace(
        Regex("[^\\p{L}\\p{N} ._-]"),"_").take(85).ifBlank{"Archive"}
    private fun download(context:Context,url:String,referer:String,target:File){
        val partial=File(target.parentFile,target.name+".part")
        var cause:Exception?=null
        repeat(4){attempt->
            try{
                val conn=URL(url).openConnection().apply{
                    connectTimeout=25000;readTimeout=45000
                    setRequestProperty("User-Agent",
                        runCatching{android.webkit.WebSettings.getDefaultUserAgent(context)}
                            .getOrDefault(
                            "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36"))
                    setRequestProperty("Referer",referer)
                    com.azrael.galleryflow.SiteSession.cookie(url)?.let {
                        setRequestProperty("Cookie",it)
                    }
                }
                if(conn is java.net.HttpURLConnection && conn.responseCode==403)
                    throw IllegalStateException("Image host HTTP 403: open the chapter in its site browser and save your session before retrying.")
                val mediaType=conn.contentType.orEmpty().substringBefore(';').lowercase()
                require(mediaType.startsWith("image/") ||
                    mediaType=="application/octet-stream"){
                    "Image URL did not return an image: ${conn.contentType}"}
                conn.getInputStream().buffered().use{input->
                    FileOutputStream(partial).use{output->input.copyTo(output,128*1024)}
                }
                require(partial.length()>128){"Image response was empty or truncated"}
                val expected=conn.contentLengthLong
                require(expected<=0 || expected==partial.length()){
                    "Image response was truncated: "+partial.length()+" / "+expected
                }
                val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true}
                android.graphics.BitmapFactory.decodeFile(partial.absolutePath,options)
                require(options.outWidth>0 && options.outHeight>0){
                    "Image response contained non-image content"
                }
                require(partial.renameTo(target)){"Cannot commit cached page"}
                return
            }catch(t:Exception){
                cause=t;partial.delete()
                if(t.message.orEmpty().contains("403") &&
                    com.azrael.galleryflow.SiteSession.bound(context,referer)){
                    try {
                        BrowserImageFetch.download(context,url,referer,partial)
                        val bounds=android.graphics.BitmapFactory.Options().apply{
                            inJustDecodeBounds=true
                        }
                        android.graphics.BitmapFactory.decodeFile(partial.absolutePath,bounds)
                        require(bounds.outWidth>0&&bounds.outHeight>0) {
                            "Browser delivered an invalid image"
                        }
                        require(partial.renameTo(target)) {
                            "Unable to save verified browser image"
                        }
                        return
                    }catch(browserError:Exception){
                        partial.delete()
                        throw IllegalStateException(
                            "Browser image retrieval failed: "+browserError.message,browserError)
                    }
                }
                if(attempt<3)Thread.sleep((attempt+1)*750L)
            }
        }
        throw cause?:IllegalStateException("Unable to retrieve image")
    }
    private fun build(context:Context,task:ChapterTask,onProgress:(Int)->Unit):String{
        val base=context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?:error("External app storage is unavailable")
        val cache=File(base,"chapter-cache/${task.id}")
        require(cache.isDirectory||cache.mkdirs()){"Unable to create page cache"}
        val chapters=ChapterScraper.chapters(task.url,context)
        require(chapters.isNotEmpty()){"No chapters discovered"}
        val out=File(cache,"complete.zip.part")
        try{
            ZipOutputStream(FileOutputStream(out)).use{zip->
                for((chapterIndex,chapter) in chapters.withIndex()){
                    val images=ChapterScraper.pageImages(chapter.url,context)
                    for((pageIndex,image) in images.withIndex()){
                        val ext=Regex("(?i)\\.(jpe?g|png|webp|gif|avif)([?#]|$)")
                            .find(image)?.groupValues?.get(1)?.lowercase()?:"jpg"
                        val page=File(cache,"%05d_%05d.%s".format(chapterIndex+1,pageIndex+1,ext))
                        if(!page.isFile||page.length()<128)download(context,image,chapter.url,page)
                        // One ZIP with flat page entries; keep chapter/page numbering in filenames.
                        val name="%05d_%s_%05d.%s".format(chapterIndex+1,
                            safeName(chapter.title),pageIndex+1,ext)
                        zip.putNextEntry(ZipEntry(name))
                        page.inputStream().buffered().use{it.copyTo(zip,128*1024)}
                        zip.closeEntry()
                    }
                    onProgress((chapterIndex+1)*90/chapters.size)
                }
            }
            require(out.length()>100){"Incomplete ZIP archive"}
            val values=ContentValues().apply{
                put(MediaStore.Downloads.DISPLAY_NAME,safeName(task.title)+".zip")
                put(MediaStore.Downloads.MIME_TYPE,"application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS+"/AsterionDownloader/Comics/")
                put(MediaStore.Downloads.IS_PENDING,1)
            }
            val uri=context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)
                ?:error("Unable to create final ZIP")
            try{
                context.contentResolver.openOutputStream(uri,"w")!!.use{dest->
                    out.inputStream().buffered().use{it.copyTo(dest,256*1024)}
                }
                val bytes=context.contentResolver.openAssetFileDescriptor(uri,"r")
                    ?.use{it.length}?:-1L
                require(bytes<0||bytes==out.length()){"ZIP copy verification failed"}
                context.contentResolver.update(uri,ContentValues().apply{
                    put(MediaStore.Downloads.IS_PENDING,0)},null,null)
            }catch(t:Exception){
                context.contentResolver.delete(uri,null,null)
                throw t
            }
            cache.listFiles()?.forEach{it.delete()}
            cache.delete()
            onProgress(100)
            return uri.toString()
        }catch(t:Exception){
            out.delete() // Complete page files survive retry and application restart.
            throw t
        }
    }
}

class ChapterArchiveService:Service(){
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
        startForeground(25481,ChapterArchives.notification(this,"Preparing archive"))
        val running=ChapterArchives.run(applicationContext){message->
            getSystemService(NotificationManager::class.java)
                .notify(25481,ChapterArchives.notification(this,message))
        }
        Thread{runCatching{running.get()};stopSelfResult(startId)}.start()
        return START_REDELIVER_INTENT
    }
}
