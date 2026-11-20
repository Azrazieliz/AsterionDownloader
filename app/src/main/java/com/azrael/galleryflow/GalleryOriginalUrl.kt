package com.azrael.galleryflow

import java.net.URI

/** Encode Unicode photo filenames only at the HTTP request boundary.
 * Keep the source's logical URL and stable database IDs unchanged.
 * toASCIIString preserves existing percent escapes and signed queries.
 */
internal object GalleryOriginalUrl {
    fun requestTarget(source:String):String {
        val uri=URI(source)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() &&
            uri.userInfo==null){"Expected publisher HTTPS original"}
        return uri.toASCIIString()
    }
}
