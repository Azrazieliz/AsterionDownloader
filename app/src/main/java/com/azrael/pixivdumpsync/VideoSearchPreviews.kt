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
    fun display(a:Activity,image:ImageView,url:String?) {
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
        workers.execute{
            val bitmap=runCatching{
                val connection=URL(url).openConnection().apply{
                    connectTimeout=6000
                    readTimeout=8000
                    setRequestProperty("User-Agent","Mozilla/5.0")
                    setRequestProperty("Accept","image/*")
                }
                require(connection.contentType.orEmpty().startsWith("image/"))
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
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,
                    BitmapFactory.Options().apply{inSampleSize=2})
            }.getOrNull()
            if(bitmap!=null){
                synchronized(lock){memory.put(url,bitmap)}
                a.runOnUiThread{
                    if(image.tag==url&&!a.isFinishing&&!a.isDestroyed){
                        image.scaleType=ImageView.ScaleType.CENTER_CROP
                        image.setImageBitmap(bitmap)
                        image.contentDescription="Video thumbnail"
                    }
                }
            }
        }
    }
}
