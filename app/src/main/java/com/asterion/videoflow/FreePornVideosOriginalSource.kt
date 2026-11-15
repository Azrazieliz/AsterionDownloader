package com.asterion.videoflow

import java.net.URI
import org.jsoup.nodes.Document

/** A real selected FreePornVideos page has an HTML5MediaEmbed format URL
 * with NO .mp4 suffix. 2026-11-13: first-party response HTTP 206,
 * MIME video/mp4, Content-Range, and MP4 ftyp header were verified.
 */
internal object FreePornVideosOriginalSource {
    fun selectedPage(url:String):Boolean=runCatching {
        val uri=URI(url)
        uri.scheme=="https" && uri.host.orEmpty().lowercase()
            .removePrefix("www.")=="freepornvideos.xxx" &&
            Regex("""^/videos/[0-9]{2,12}/[a-zA-Z0-9_-]{5,230}/?$""")
                .matches(uri.path.orEmpty())
    }.getOrDefault(false)

    fun firstPartyFormat(page:String,media:String):Boolean=runCatching{
        if(!selectedPage(page))return false
        val uri=URI(media)
        uri.scheme=="https" && uri.userInfo==null && uri.port==-1 &&
            uri.host.orEmpty().lowercase().removePrefix("www.")==
                "freepornvideos.xxx" &&
            uri.path.orEmpty().length>1 && media!=page
    }.getOrDefault(false)
    /** The selected HTML5 player publishes quality-labelled first-party
     * /get_file/.../<selected ID>_2160m.mp4/ links, including a trailing '/'.
     * Previously we discarded them and invoked Python for the article URL.
     * Select only an actual <video><source> belonging to the chosen ID.
     */
    internal fun selectedOriginal(doc:Document,selected:String):String? {
        if(!selectedPage(selected))return null
        val id=Regex("""^/videos/([0-9]{2,12})/""")
            .find(URI(selected).path.orEmpty())?.groupValues?.get(1)?:return null
        val path=Regex(
            """(?i)^/get_file/[a-z0-9_/%.-]{15,400}/"""+
                Regex.escape(id)+"""_(2160|1440|1080|720|480|360)m\\.mp4/?$""")
        val formats=doc.select("video source[src][type=video/mp4],"+
            "video source[src$=.mp4],video[src]").mapNotNull{element->
            val url=runCatching{
                URI(selected).resolve(element.attr("src")).toString()
            }.getOrNull()?:return@mapNotNull null
            val uri=runCatching{URI(url)}.getOrNull()
                ?:return@mapNotNull null
            val matched=path.matchEntire(uri.path.orEmpty())
                ?:return@mapNotNull null
            if(uri.rawQuery!=null || !firstPartyFormat(selected,url))
                return@mapNotNull null
            (matched.groupValues[1].toIntOrNull()?:0) to url
        }.distinctBy{it.second}
        return formats.maxByOrNull{it.first}?.second
    }

}
