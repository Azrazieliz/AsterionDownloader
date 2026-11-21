package com.asterion.videoflow

import java.net.URI
import java.util.Locale
import org.jsoup.Jsoup

/** Bubblecum's real selected KVS player publishes a signed original under
 * its own flashvars.video_url. The same HTML contains trailer MP4s under
 * /contents/videos_screenshots/ which must NEVER be selected.
 */
internal object BubblecumSelectedOriginalSource {
    private const val HOST="bubblecum.lol"
    private val fullPath=Regex("""(?i)^/get_file/[0-9]{1,4}/[A-Za-z0-9_/%.-]{18,800}\.mp4/?$""")
    private val selectedVideoUrl=Regex(
        """(?is)(?:^|[,{]\s*)["']?video_url["']?\s*:\s*["'](https?://[^"'<>]{15,2200})["']""")
    private val selectedVideoId=Regex(
        """(?is)(?:^|[,{]\s*)["']?video_id["']?\s*:\s*["']?([0-9]{1,12})""")

    fun verifiedMedia(selected:String,media:String):Boolean {
        if(!VideoSiteProfiles.isVideoPage(selected))return false
        val page=runCatching{URI(selected)}.getOrNull()?:return false
        val u=runCatching{URI(media.replace("\\/","/"))}.getOrNull()?:return false
        return page.scheme=="https" && page.userInfo==null &&
            page.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==HOST &&
            u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==HOST &&
            u.rawFragment==null && fullPath.matches(u.path.orEmpty()) &&
            !FullLengthVideoPolicy.isExplicitPreview("",media)
    }

    fun published(selected:String,html:String):List<String> {
        if(!VideoSiteProfiles.isVideoPage(selected) ||
            runCatching{URI(selected).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")}.getOrDefault("")!=HOST)return emptyList()
        val doc=Jsoup.parse(html,selected)
        // One real article's player, never related-card <video> trailers.
        if(doc.select("#kt_player").size!=1)return emptyList()
        val scripts=doc.select("script:not([src])").map{
            it.data().ifBlank{it.html()}
        }.filter{
            it.contains("kt_player") &&
                it.contains("video_url") &&
                it.contains("video_id")
        }
        if(scripts.size!=1)return emptyList()
        val script=scripts.single()
        if(selectedVideoId.findAll(script).map{it.groupValues[1]}
                .distinct().count()!=1)return emptyList()
        val paths=selectedVideoUrl.findAll(script).map{
            it.groupValues[1].replace("\\/","/").replace("&amp;","&")
        }.distinct().toList()
        return paths.singleOrNull()?.takeIf{verifiedMedia(selected,it)}
            ?.let{listOf(it)}.orEmpty()
    }
}
