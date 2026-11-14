package com.asterion.videoflow

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Publisher-specific Rule34Video extraction.
 *
 * Rule34Video publishes original download links in quality-labelled
 * <a href="...?download=true">MP4 1080p</a> entries. Those are NOT
 * <video src> tags; a generic HTML5 / iframe parser misses them.
 * Mirrors the official page's actual download markup, not a fabricated CDN.
 */
internal object Rule34VideoOriginalSource {
    data class Original(val url:String,val quality:Int,val format:String)
    private val videoPath=Regex("""^/videos?/([0-9]{1,12})(?:/[^?#]*)?/?$""")
    // Real publisher labels concatenate quality and file size:
    // "MP4 720p3 MB", not only "MP4 720p". The prior word boundary
    // silently discarded EVERY original on the selected live page.
    private val videoLabel=Regex(
        """(?i)\b(mp4|m4v|webm)\s+(\d{3,4})\s*p(?=\b|\d+\s*(?:MB|GB)\b)""")
    fun videoId(page:String):String?{
        val u=runCatching{URI(page)}.getOrNull()?:return null
        if(u.scheme!="https" || u.userInfo!=null || u.port!=-1 ||
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")!="rule34video.com")
            return null
        return videoPath.matchEntire(u.path.orEmpty())?.groupValues?.get(1)
    }
    /**
     * Exact selected publisher /get_file response is a video/mp4 even though
     * its URL has no media extension. The native lane must inspect its real
     * Content-Type, Content-Range and MP4 header rather than calling yt-dlp
     * on a signed, short-lived file endpoint.
     */
    fun firstPartyDownload(selected:String,media:String):Boolean {
        if(videoId(selected)==null)return false
        val uri=runCatching{URI(media)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")!="rule34video.com")
            return false
        if(!uri.path.orEmpty().startsWith("/get_file/"))return false
        return uri.rawQuery.orEmpty().split('&').any{
            it.equals("download=true",ignoreCase=true)
        }
    }
    fun isPublisherMediaUrl(media:String):Boolean =
        firstPartyDownload("https://rule34video.com/video/1/",media)

    /** No listing links, ad/preview anchors, or guessed /get_file URLs. */
    fun parse(html:String,page:String):List<Original> {
        if(videoId(page)==null)return emptyList()
        val doc=Jsoup.parse(html,page)
        val found=LinkedHashMap<String,Original>()
        for(a in doc.select("a[href]").take(1_500)){
            val label=videoLabel.find(a.text().trim())?:continue
            val raw=a.attr("href")
            val url=runCatching{URI(page).resolve(raw).toString()}.getOrNull()
                ?:continue
            val u=runCatching{URI(url)}.getOrNull()?:continue
            if(u.scheme!="https" || u.userInfo!=null || u.host.isNullOrBlank() ||
                !WebDiscovery.isUrl(url))continue
            val params=u.rawQuery.orEmpty().split('&').map{it.lowercase(Locale.ROOT)}
            if("download=true" !in params ||
                !firstPartyDownload(page,url))continue
            if(FullLengthVideoPolicy.isExplicitPreview("",url))continue
            val quality=label.groupValues[2].toIntOrNull()?:continue
            if(quality !in 240..4320)continue
            found.putIfAbsent(url,Original(url,quality,
                label.groupValues[1].lowercase(Locale.ROOT)))
        }
        return found.values.sortedWith(compareByDescending<Original>{it.quality}
            .thenBy{it.url})
    }
}
