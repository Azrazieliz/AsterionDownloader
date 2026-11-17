package com.asterion.videoflow

import java.net.URI

/** Only an explicitly selected HTTPS manifest is routed to typed HLS first.
 * Browser pages, arbitrary JS strings, thumbnails and media-lookalike hosts
 * are never promoted to downloaded originals by this dispatch check.
 */
internal object VideoHlsDispatch {
    fun isSelectedManifest(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull() ?: return false
        return uri.scheme=="https" && !uri.host.isNullOrBlank() &&
            uri.userInfo==null && uri.fragment==null && uri.port==-1 &&
            uri.path.orEmpty().matches(Regex("""(?i)^/[^?#]{5,2000}\.(?:m3u8|mpd)$"""))
    }
}
