package com.azrael.pixivdumpsync

import java.net.URI
import java.util.Locale

/** This filter only governs native first-media cover fallback; publisher
 * preview image URLs retain their existing independent source-verified path.
 */
internal object KyoraGalleryCoverPolicy {
    private val imageTypes=setOf("jpg","jpeg","png","gif","webp","avif")
    fun isImageUrl(raw:String):Boolean=runCatching {
        val u=URI(raw)
        u.scheme.equals("https",true) && u.userInfo==null &&
            !u.host.isNullOrBlank() &&
            u.path.orEmpty().substringAfterLast('.',missingDelimiterValue="")
                .lowercase(Locale.ROOT) in imageTypes
    }.getOrDefault(false)
}
