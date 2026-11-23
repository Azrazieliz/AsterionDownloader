package com.azrael.pixivdumpsync

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.view.Gravity
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** A publisher returning an HTTP 5xx/429 for its poster page should
 * not be hammered by dozens of cards in the same render. Keep this
 * independent from original-video downloads and direct poster URLs.
 */
internal object PosterHostCooldown {
    private val suspended=LinkedHashMap<String,Long>()
    private fun host(value:String)=runCatching{
        java.net.URI(value).host.orEmpty().lowercase(java.util.Locale.ROOT)
    }.getOrDefault("")
    @Synchronized fun allowed(page:String,now:Long=System.currentTimeMillis()):Boolean {
        val key=host(page)
        return key.isNotBlank() && (suspended[key]?:0L)<=now
    }
    @Synchronized fun record(page:String,error:Throwable,
                              now:Long=System.currentTimeMillis()):Boolean {
        val status=Regex("""(?:HTTP[ :]\\s*|poster_http_)(429|500|502|503|504)\\b""",
            RegexOption.IGNORE_CASE)
            .find(error.message.orEmpty())?:return false
        if(status.groupValues[1].isBlank())return false
        val key=host(page).takeIf{it.isNotBlank()}?:return false
        suspended[key]=now+20_000L
        while(suspended.size>128){
            val oldest=suspended.keys.iterator()
            if(oldest.hasNext()){oldest.next();oldest.remove()}else break
        }
        return true
    }
}

/** Bounded 3-thread video-result thumbnail loader; never blocks the UI. */
object VideoSearchPreviews {
    // A screen may queue 120 video cards. The previous queue held 72 and
    // DiscardOldestPolicy silently removed the FIRST cards before loading.
    // Keep a bounded first-visible-first queue without UI-thread network work.
    private val workers=PreviewLoadExecutor.create(3)
    private val memory=object:LruCache<String,Bitmap>(12*1024*1024){
        override fun sizeOf(key:String,value:Bitmap):Int=value.byteCount
    }
    private val lock=Any()
    // Missing/blocked poster pages were fetched again on every card repaint,
    // saturating all three thumbnail lanes and delaying newer visible cards.
    // Expire negatives quickly so a signed poster or restored DNS can recover.
    private val unavailableUntil=LinkedHashMap<String,Long>()
    private const val POSTER_FAILURE_RECHECK_MS=90_000L
    fun display(a:Activity,image:ImageView,url:String?,referer:String?=null) {
        image.setImageResource(R.drawable.ic_source_video)
        image.scaleType=ImageView.ScaleType.CENTER_INSIDE
        image.contentDescription="Video preview unavailable"
        // A recycled view can previously retain the preceding video's tag
        // when this result has no poster. That let a stale async callback
        // overwrite the honest placeholder with ANOTHER video's image.
        image.tag=null
        val validUrl=url?.takeIf{SearchRanking.validUrl(it)}
        // A genuine Rule34Video download can exist without a catalog poster.
        // Obtain only the exact selected page's publisher-attested OG/poster
        // metadata; never fabricate a thumbnail from an unrelated video.
        val rule34Page=referer?.takeIf{
            com.asterion.videoflow.Rule34VideoOriginalSource.videoId(it)!=null
        }
        val selectedPage=referer?.takeIf{
            it.startsWith("https://") && SearchRanking.validUrl(it) &&
                (SearchRanking.isVideoUrl(it) ||
                    com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it))
        }
        // OnScreens listing cards have repeatedly attributed a shared
        // thumbnail to different recordings. The genuine selected article
        // owns its preview: key by exact recording URL, not shared listing
        // cover URL, and verify its first-party OG/poster independently.
        val exactOnScreens=selectedPage?.takeIf{
            SiteControls.key(it)=="onscreens.me"
        }
        val key=if(exactOnScreens!=null)
            "onscreens:selected:"+exactOnScreens else validUrl?:selectedPage?:return
        // Tag before consulting the memory cache. A recycled view with a
        // cached new poster must reject an older in-flight image callback.
        image.tag=key
        synchronized(lock){memory.get(key)}?.let{
            image.scaleType=ImageView.ScaleType.CENTER_CROP
            image.setImageBitmap(it)
            image.contentDescription="Video thumbnail"
            return
        }
        val skip=synchronized(lock){
            val expiry=unavailableUntil[key]
            if(expiry!=null && expiry>System.currentTimeMillis())true
            else{
                unavailableUntil.remove(key)
                false
            }
        }
        if(skip)return
        // Downloads re-renders lightweight temporary card trees to update
        // text/progress in place. Those throwaway image views must NOT enqueue
        // network reads or retain 2 MB response buffers in the poster pool.
        // A real attached view starts its fetch once; detached snapshots
        // remain placeholders, with the same stable poster identity tag.
        if(!image.isAttachedToWindow){
            image.addOnAttachStateChangeListener(
                object:android.view.View.OnAttachStateChangeListener{
                    override fun onViewAttachedToWindow(v:android.view.View){
                        image.removeOnAttachStateChangeListener(this)
                        if(!a.isFinishing && !a.isDestroyed && image.tag==key)
                            display(a,image,url,referer)
                    }
                    override fun onViewDetachedFromWindow(v:android.view.View){}
                })
            return
        }
        val app=a.applicationContext
        val view=java.lang.ref.WeakReference(image)
        // Do not retain Activity/ImageView references in the unbounded
        // thumbnail network queue after the app enters the background.
        workers.execute{
            var bitmap:Bitmap?=null
            var failure:String?=null
            // Fallback is lazy: existing verified poster URLs load first.
            // Only a failed/missing Rule34Video poster triggers an additional
            // selected-page request. All work remains off the UI thread.
            val candidates=sequence<String>{
                if(exactOnScreens!=null){
                    // Never reuse a possibly duplicated listing cover. The
                    // selected OnScreens recording must actually publish its
                    // own OG-image/poster. Otherwise show the honest icon.
                    runCatching{
                        PublisherSearchPoster.fetch(app,exactOnScreens)
                    }.getOrNull()?.let{yield(it)}
                }else validUrl?.let{verifiedPoster->
                    yieldAll(PublisherPreviewFallback.candidates(verifiedPoster,referer))
                }
                rule34Page?.let{source->
                    val poster=if(!PosterHostCooldown.allowed(source))null else
                        runCatching{
                            Rule34PosterEvidence.readPoster(app,source)
                        }.onFailure{problem->
                            PosterHostCooldown.record(source,problem)
                            failure="selected_page_"+problem.javaClass.simpleName+
                                ": "+problem.message.orEmpty().take(80)
                        }.getOrNull()
                    if(poster!=null && poster!=validUrl)yield(poster)
                }
                selectedPage?.takeIf{
                    it!=rule34Page && it!=exactOnScreens
                }?.let{source->
                    val poster=if(!PosterHostCooldown.allowed(source))null else
                        runCatching{
                            PublisherSearchPoster.fetch(app,source)
                        }.onFailure{problem->
                            PosterHostCooldown.record(source,problem)
                            failure="publisher_poster_"+problem.javaClass.simpleName+
                                ": "+problem.message.orEmpty().take(80)
                        }.getOrNull()
                    if(poster!=null && poster!=validUrl)yield(poster)
                }
            }
            for(candidateUrl in candidates){
                bitmap=runCatching{
                // Respect an explicitly configured poster/CDN route; otherwise
                // inherit the selected publisher's existing route.
                val proxy=if(!referer.isNullOrBlank())
                    com.asterion.videoflow.NetworkRoute.javaProxyForSelected(
                        app,referer,candidateUrl)
                    else com.asterion.videoflow.NetworkRoute.javaProxy(
                        app,candidateUrl)
                val connection=(if(proxy!=null)URL(candidateUrl).openConnection(proxy)
                    else URL(candidateUrl).openConnection()).apply{
                    connectTimeout=6000
                    readTimeout=8000
                    setRequestProperty("User-Agent",
                        com.asterion.videoflow.NativeMediaDownloader.USER_AGENT)
                    setRequestProperty("Accept","image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                    if(!referer.isNullOrBlank() && SearchRanking.validUrl(referer)) {
                        // A poster CDN often accepts the exact publisher media
                        // page as Referer, not the catalog's homepage.
                        setRequestProperty("Referer",referer)
                        val sameHost=runCatching{
                            val poster=java.net.URI(candidateUrl).host.orEmpty().lowercase()
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
                val status=(connection as? java.net.HttpURLConnection)?.responseCode
                if(status!=null && status !in 200..299)
                    throw java.io.IOException("poster_http_"+status)
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
                }.onFailure{problem->
                    failure=problem.javaClass.simpleName+": "+
                        problem.message.orEmpty().take(100)
                }.getOrNull()
                if(bitmap!=null)break
            }
            val ready=bitmap
            // A missing first-party poster is a diagnosable search
            // failure, even when no thumbnail URL was present in the
            // third-party index. Previously only Rule34Video recorded it,
            // leaving diagnostics incorrectly claiming zero failures.
            if(ready==null && selectedPage!=null)
                ThumbnailFailureJournal.record(app,selectedPage,
                    failure?:"selected_page_did_not_publish_poster")
            if(ready==null) synchronized(lock){
                unavailableUntil[key]=System.currentTimeMillis()+
                    POSTER_FAILURE_RECHECK_MS
                while(unavailableUntil.size>400){
                    val old=unavailableUntil.keys.iterator()
                    if(old.hasNext()){old.next();old.remove()}else break
                }
            }
            if(ready!=null){
                synchronized(lock){
                    unavailableUntil.remove(key)
                    memory.put(key,ready)
                }
                view.get()?.post{
                    val target=view.get()
                    if(target!=null && target.tag==key &&
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
