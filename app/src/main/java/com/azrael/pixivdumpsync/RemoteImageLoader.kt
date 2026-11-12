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

object RemoteImageLoader {
    private val executor = Executors.newFixedThreadPool(4)

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
        if (url.isNullOrBlank()) return
        val imageKey=if(preserveOriginal)"original-card:"+url else url
        imageView.tag = imageKey
        val app=context.applicationContext
        val target=java.lang.ref.WeakReference(imageView)

        memory.get(imageKey)?.let {
            imageView.setImageBitmap(it)
            return
        }

        executor.execute {
            val bitmap = runCatching {
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

                        file.outputStream().buffered().use { output ->
                            conn.inputStream.use { input ->
                                input.copyTo(output, 64 * 1024)
                            }
                        }
                    } finally {
                        runCatching { conn.disconnect() }
                    }
                }

                decodeSampled(file, if(preserveOriginal) 1024 else 256)
                    .also { decoded -> if(decoded==null)file.delete() }
            }.getOrNull()

            if (bitmap != null) {
                memory.put(imageKey, bitmap)
                target.get()?.post {
                    val current=target.get()
                    if(current!=null && current.tag==imageKey &&
                        current.isAttachedToWindow){
                        current.setImageBitmap(bitmap)
                    }
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
