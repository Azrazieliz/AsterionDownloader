package com.azrael.pixivdumpsync

import java.net.URI
import java.util.Locale

/** A publisher-attested alternative for media that JAV.GURU's
 * CDN currently returns as HTTP 403; same WP upload path was verified
 * available on the publisher's official hostname. Real 2026-11-13\n * PNG/JPEG bytes confirm both the poster and /thumbs/ upload layouts.
 */
internal object PublisherPreviewFallback {
    fun candidates(poster:String,referer:String?):List<String> {
        val p=runCatching{URI(poster)}.getOrNull()
        val from=runCatching{URI(referer.orEmpty())}.getOrNull()
        val cdn=p?.host.orEmpty().lowercase(Locale.ROOT)
        val publisher=from?.host.orEmpty().lowercase(Locale.ROOT)
            .removePrefix("www.")
        val path=p?.rawPath.orEmpty()
        val valid= p!=null && p.scheme=="https" && p.rawUserInfo==null &&
            p.port==-1 && publisher=="jav.guru" &&
            cdn in setOf("cdn.javmiku.com","javmiku.com","cdn.javnorth.com") &&
            path.startsWith("/wp-content/uploads/") &&
            path.matches(Regex("""(?i)^/wp-content/uploads/(?:thumbs/)?[0-9]{4}/[0-9]{2}/[a-z0-9_.-]+\.(?:jpg|jpeg|png|webp)$"""))
        if(!valid)return listOf(poster)
        // Verified direct image bytes on the original and first-party URLs.\n        // The query string (possibly tracking/signed metadata) is never
        // forwarded to another host. Only a safe original upload path.
        return listOf("https://jav.guru"+path,poster)
    }
}
