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
    private val videoLabel=Regex("""(?i)\b(mp4|m4v|webm)\s+(\d{3,4})\s*p\b""")
    fun videoId(page:String):String?{
        val u=runCatching{URI(page)}.getOrNull()?:return null
        if(u.scheme!="https" || u.userInfo!=null || u.port!=-1 ||
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")!="rule34video.com")
            return null
        return videoPath.matchEntire(u.path.orEmpty())?.groupValues?.get(1)
    }
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
            if("download=true" !in params)continue
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
