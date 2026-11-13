package com.asterion.videoflow

import java.net.URI

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
}
