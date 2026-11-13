package com.azrael.pixivdumpsync

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** Bounded 3-thread video-result thumbnail loader; never blocks the UI. */
object VideoSearchPreviews {
    private val workers=Executors.newFixedThreadPool(3)
    private val memory=object:LruCache<String,Bitmap>(12*1024*1024){
        override fun sizeOf(key:String,value:Bitmap):Int=value.byteCount
    }
    private val lock=Any()
    fun display(a:Activity,image:ImageView,url:String?,referer:String?=null) {
        image.setImageResource(R.drawable.ic_source_video)
        image.scaleType=ImageView.ScaleType.CENTER_INSIDE
        image.contentDescription="Video preview unavailable"
        if(url==null||!SearchRanking.validUrl(url))return
        synchronized(lock){memory.get(url)}?.let{
            image.scaleType=ImageView.ScaleType.CENTER_CROP
            image.setImageBitmap(it)
            image.contentDescription="Video thumbnail"
            return
        }
        image.tag=url
        val app=a.applicationContext
        val view=java.lang.ref.WeakReference(image)
        // Do not retain Activity/ImageView references in the unbounded
        // thumbnail network queue after the app enters the background.
        workers.execute{
            var bitmap:Bitmap?=null
            // Jav Guru's advertised CDN currently answers HTTP 403 for real
            // poster bytes. Use its first-party published WP image path first,
            // falling back to the original CDN only when necessary.
            for(candidateUrl in PublisherPreviewFallback.candidates(url,referer)){
                bitmap=runCatching{
                val proxy=com.asterion.videoflow.NetworkRoute.javaProxy(
                    app,referer?:url)
                val connection=(if(proxy!=null)URL(candidateUrl).openConnection(proxy)
                    else URL(candidateUrl).openConnection()).apply{
                    connectTimeout=6000
                    readTimeout=8000
                    setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
                    setRequestProperty("Accept","image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if(!referer.isNullOrBlank() && SearchRanking.validUrl(referer)) {
                        // A poster CDN often accepts the exact publisher media
                        // page as Referer, not the catalog's homepage.
                        setRequestProperty("Referer",referer)
                        val sameHost=runCatching{
                            val poster=java.net.URI(url).host.orEmpty().lowercase()
                            val publisher=java.net.URI(referer).host.orEmpty().lowercase()
                            poster==publisher
                        }.getOrDefault(false)
                        // Never expose browser cookies to third-party artwork
                        // hosts or unrelated video player / advertising sites.
                        if(sameHost)
                            com.azrael.galleryflow.SiteSession.cookie(app,referer)
                                ?.takeIf{it.isNotBlank()}
                                ?.let{setRequestProperty("Cookie",it)}
                    }
                }
                val mime=connection.contentType.orEmpty().lowercase()
                val bytes=connection.getInputStream().use{input->
                    val buffer=java.io.ByteArrayOutputStream()
                    val chunk=ByteArray(16384)
                    while(buffer.size()<2_000_000){
                        val n=input.read(chunk,0,minOf(chunk.size,2_000_000-buffer.size()))
                        if(n<0)break
                        buffer.write(chunk,0,n)
                    }
                    buffer.toByteArray()
                }
                require(bytes.size in 200..2_000_000)
                val jpeg=bytes[0]==0xff.toByte() && bytes[1]==0xd8.toByte()
                val png=bytes[0]==0x89.toByte() && bytes[1]==0x50.toByte()
                val webp=String(bytes,0,4)=="RIFF" &&
                    String(bytes,8,4)=="WEBP"
                require(mime.startsWith("image/") || jpeg || png || webp){
                    "Publisher returned HTML/challenge instead of poster bytes"
                }
                // Two-megabyte JPEGs can expand to enormous Android bitmaps;
                // decode dimensions first and scale to the 85dp preview card.
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..20_000 &&
                    bounds.outHeight in 1..20_000){
                    "Invalid or oversized image header"
                }
                var sample=1
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>350)
                    sample*=2
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                    BitmapFactory.Options().apply{
                        inSampleSize=sample
                        inPreferredConfig=Bitmap.Config.RGB_565
                    })
                }.getOrNull()
                if(bitmap!=null)break
            }
            val ready=bitmap
            if(ready!=null){
                synchronized(lock){memory.put(url,ready)}
                view.get()?.post{
                    val target=view.get()
                    if(target!=null && target.tag==url &&
                        target.isAttachedToWindow){
                        target.scaleType=ImageView.ScaleType.CENTER_CROP
                        target.setImageBitmap(ready)
                        target.contentDescription="Video thumbnail"
                    }
                }
            }
        }
    }
}
