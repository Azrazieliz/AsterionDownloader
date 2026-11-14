package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Verified original transfer from the first-party WatchPorn published page.
 * A preview tile alone is never an original; the page must independently
 * advertise the same published full duration in HTML meta and JSON-LD.
 * Actual saved media duration is still validated by MediaMetadataRetriever.
 */
internal object WatchPornOriginalSource {
    data class Original(val mediaUrl:String,val durationSeconds:Long)

    fun isExactPage(url:String):Boolean=runCatching {
        val uri=URI(url)
        uri.scheme=="https" && uri.userInfo==null && uri.port==-1 &&
            uri.host=="watchporn.to" &&
            Regex("""^/video/[0-9]{2,10}/[a-zA-Z0-9_-]{2,250}/?$""")
                .matches(uri.path.orEmpty())
    }.getOrDefault(false)

    fun parse(html:String,pageUrl:String):Original? {
        if(!isExactPage(pageUrl))return null
        val doc=Jsoup.parse(html,pageUrl)
        val duration=doc.selectFirst("meta[itemprop=duration]")
            ?.attr("content")?.toLongOrNull()?.takeIf{it in 1L..(24L*60*60)}
            ?:return null
        val jsonDurations=doc.select("script[type=application/ld+json]")
            .mapNotNull{tag->
                Regex(""""duration"\s*:\s*"PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?"""")
                    .find(tag.data().ifBlank{tag.html()})
                    ?.let{match->
                        val hours=match.groupValues[1].toLongOrNull()?:0L
                        val minutes=match.groupValues[2].toLongOrNull()?:0L
                        val seconds=match.groupValues[3].toLongOrNull()?:0L
                        hours*3600L+minutes*60L+seconds
                    }
            }
        if(jsonDurations.none{it==duration})return null
        val media=WebDiscovery.watchPornDirect(html)?:return null
        val u=runCatching{URI(media)}.getOrNull()?:return null
        if(u.scheme!="https" || u.host!="watchporn.to" ||
            u.userInfo!=null || !u.path.orEmpty().contains("/get_file/") ||
            !u.path.orEmpty().contains(".mp4"))return null
        return Original(media,duration)
    }

    fun resolve(pageUrl:String,context:Context?=null):Original? {
        if(!isExactPage(pageUrl))return null
        val response=Jsoup.connect(pageUrl)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
            .ignoreHttpErrors(true).followRedirects(false)
            .timeout(16_000).maxBodySize(3_000_000)
            .apply {
                NetworkRoute.javaProxy(context,pageUrl)?.let{proxy(it)}
                SiteSession.cookie(pageUrl)?.let{header("Cookie",it)}
            }
            .execute()
        if(response.statusCode() !in 200..299)return null
        if(response.url().host!="watchporn.to")return null
        return parse(response.body(),pageUrl)
    }
}
