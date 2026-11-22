package com.azrael.pixivdumpsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The cache contains re-fetchable *previews only*, never original downloaded
 * media. Prune by last use; files recently created by concurrent preview
 * requests are retained to avoid interfering with active writes. No other
 * application directory or resumable transfer is ever touched.
 */
/** Refuse oversized preview payloads before they fill private storage.
 * Downloads are separate and are never subject to this thumbnail limit.
 */
internal object ThumbnailPayloadLimit {
    fun copy(input:java.io.InputStream,output:java.io.OutputStream,
             maxBytes:Long):Long {
        require(maxBytes>0)
        val chunk=ByteArray(64*1024)
        var total=0L
        while(true){
            val n=input.read(chunk,0,minOf(chunk.size,
                (maxBytes-total+1).coerceAtMost(chunk.size.toLong()).toInt()))
            if(n<0)return total
            if(n==0)continue
            total+=n
            if(total>maxBytes)
                throw java.io.IOException("Thumbnail exceeds private cache limit")
            output.write(chunk,0,n)
        }
    }
}

internal object ThumbnailCacheRetention {
    private val hashedName=Regex("^[a-f0-9]{64}$")
    fun prune(dir:File,protected:File,maxBytes:Long=256L*1024*1024,
              maxEntries:Int=2500,now:Long=System.currentTimeMillis()):Int {
        if(!dir.isDirectory)return 0
        val entries=dir.listFiles()?.filter{
            it.isFile && hashedName.matches(it.name)
        }?.sortedWith(compareBy<File>{it.lastModified()}.thenBy{it.name})
            ?:return 0
        var total=entries.sumOf{it.length().coerceAtLeast(0L)}
        var count=entries.size
        var removed=0
        for(entry in entries){
            if(total<=maxBytes && count<=maxEntries)break
            if(entry.absolutePath==protected.absolutePath ||
                now-entry.lastModified()<120_000L)continue
            val size=entry.length().coerceAtLeast(0L)
            if(entry.delete()){
                total=(total-size).coerceAtLeast(0L)
                count--
                removed++
            }
        }
        return removed
    }
}

object RemoteImageLoader {
    private val trimLock=Any()
    @Volatile private var lastTrimAt:Long=0L
    private fun trimPreviewCache(dir:File,active:File){
        val now=android.os.SystemClock.elapsedRealtime()
        synchronized(trimLock){
            if(lastTrimAt!=0L && now-lastTrimAt<90_000L)return
            lastTrimAt=now
            runCatching{ThumbnailCacheRetention.prune(dir,active)}
        }
    }
    // Creator/gallery screens also present more than 96 cards. Never evict
    // the earliest visible thumbnails merely because later rows are queued.
    private val executor = PreviewLoadExecutor.create(4)

    private val memory = object : LruCache<String, Bitmap>(
        minOf(
            16 * 1024,
            (Runtime.getRuntime().maxMemory() / 1024L / 16L).toInt().coerceAtLeast(4 * 1024)
        )
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    fun load(
        context: Context,
        imageView: ImageView,
        url: String?,
        referer: String = "https://www.pixiv.net/",
        preserveOriginal: Boolean = false
    ) {
        if (url.isNullOrBlank()) {
            // Reused artist cards must never retain a previous artist's image.
            if (imageView.tag != null) {
                imageView.tag = null
                imageView.setImageDrawable(null)
            }
            return
        }
        val imageKey=if(preserveOriginal)"original-card:"+url else url
        if (imageView.tag != null && imageView.tag != imageKey)
            imageView.setImageDrawable(null)
        imageView.tag = imageKey
        val app=context.applicationContext
        val target=java.lang.ref.WeakReference(imageView)

        memory.get(imageKey)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            // A list refresh can rebind this view to another artist while a
            // request waits in the executor. Never fetch or paint stale work.
            if (target.get()?.tag != imageKey) return@execute
            // The same thumbnail may occur on multiple cards in a refresh.
            // Coalesce cache writes by URL without serializing all thumbnails.
            val bitmap = FileSaveLockPolicy.forFilename("thumbnail:"+imageKey) {
                memory.get(imageKey) ?: runCatching {
                val dir = File(app.cacheDir, "kuroha_images").apply { mkdirs() }
                val file = File(dir, sha256(url))

                if (!file.exists() || file.length() <= 0L) {
                    NetworkCookies.install(app)
                    val proxy=com.asterion.videoflow.NetworkRoute.javaProxy(
                        app,referer)
                        ?:com.asterion.videoflow.NetworkRoute.javaProxy(app,url)
                    val conn=(if(proxy!=null)URL(url).openConnection(proxy)
                        else URL(url).openConnection()) as HttpURLConnection
                    try {
                        conn.instanceFollowRedirects = true
                        conn.connectTimeout = 8_000
                        conn.readTimeout = 14_000
                        val mediaHost=runCatching{URL(url).host.lowercase()}.getOrDefault("")
                        conn.setRequestProperty("User-Agent",
                            if(mediaHost.endsWith(".pximg.net")) PixivApi.USER_AGENT
                            else "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/125.0.0.0 Mobile Safari/537.36")
                        val resolvedReferer=when{
                            mediaHost=="pbs.twimg.com" || mediaHost.endsWith(".twimg.com") ->
                                "https://x.com/"
                            mediaHost=="i.pximg.net" || mediaHost.endsWith(".pximg.net") ->
                                "https://www.pixiv.net/"
                            (mediaHost=="mitaku.net" ||
                                mediaHost.endsWith(".mitaku.net")) &&
                                SiteControls.key(referer)=="kiutaku.com" ->
                                // Verified: image CDN returns HTTP 403 with
                                // kiutaku.com Referer and HTTP 200 with its
                                // own publishing host. Do not send unrelated
                                // site cookies; only correct the public header.
                                "https://mitaku.net/"
                            else->referer
                        }
                        conn.setRequestProperty("Referer", resolvedReferer)
                        conn.setRequestProperty(
                            "Accept",
                            "image/avif,image/webp,image/apng,image/*,*/*;q=0.8"
                        )

                        val code = conn.responseCode
                        if (code !in 200..299 ||
                            !conn.contentType.orEmpty().startsWith("image/",true)) {
                            return@runCatching null
                        }

                        // Cache only a bounded, fully received preview.
                        // The old unbounded copyTo could cache a huge image or
                        // leave a partial hashed file after a network failure.
                        val maxPreviewBytes=if(preserveOriginal)
                            64L*1024*1024 else 24L*1024*1024
                        if(conn.contentLengthLong>maxPreviewBytes)
                            return@runCatching null
                        val temporary=File(dir,file.name+".part")
                        try{
                            temporary.outputStream().buffered().use { output ->
                                conn.inputStream.use { input ->
                                    ThumbnailPayloadLimit.copy(input,output,maxPreviewBytes)
                                }
                            }
                            if(!temporary.renameTo(file)){
                                throw java.io.IOException("Cannot commit preview cache")
                            }
                        }finally{
                            temporary.delete()
                        }
                    } finally {
                        runCatching { conn.disconnect() }
                    }
                }

                decodeSampled(file, if(preserveOriginal) 1024 else 256)
                    .also { decoded ->
                        if(decoded==null)file.delete()
                        else trimPreviewCache(dir,file)
                    }
                }.getOrNull().also { ready ->
                    // Publish the successful bitmap before releasing the
                    // per-URL lock so other cards never redownload it.
                    if (ready != null) memory.put(imageKey, ready)
                }
            }

            if (bitmap != null) {
                target.get()?.post {
                    val current=target.get()
                    // A request can finish during a layout refresh before
                    // attachment. Painting the correct tagged view is safe
                    // and prevents a permanently blank card until next visit.
                    if(current!=null && current.tag==imageKey)
                        current.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun decodeSampled(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        val largest = max(bounds.outWidth, bounds.outHeight)
        while (largest / (sample * 2) >= targetPx) {
            sample *= 2
        }

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
