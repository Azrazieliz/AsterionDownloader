package com.asterion.videoflow

import java.net.URI

/** Only an explicitly selected HTTPS manifest is routed to typed HLS first.
 * Browser pages, arbitrary JS strings, thumbnails and media-lookalike hosts
 * are never promoted to downloaded originals by this dispatch check.
 */
internal object VideoHlsDispatch {
    /** Resolve short-lived selected signed HLS links only AFTER the Python
     * runtime is ready. Starting Chaquopy and validating yt-dlp after
     * receiving a signed manifest can expire that very media URL on Android.
     */
    fun needsRuntimeBeforeSource(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.fragment!=null)return false
        val host=uri.host.orEmpty().lowercase().removePrefix("www.")
        if(host in setOf("pornhub.com","m.pornhub.com",
                "fr.pornhub.com","en.pornhub.com")){
            if(uri.path!="/view_video.php")return false
            val keys=uri.rawQuery.orEmpty().split('&').filter{
                it.startsWith("viewkey=")
            }
            return keys.size==1 && keys.single().removePrefix("viewkey=")
                .matches(Regex("[A-Za-z0-9_-]{5,50}"))
        }
        // The selected HentaiHaven episode also resolves its publisher
        // HLS only after the page is read. Avoid expiring that manifest
        // while starting the embedded Python runtime on Android.
        return (host=="jav.guru" && VideoSiteProfiles.isVideoPage(url)) ||
            (host=="hentaihaven.xxx" &&
                HentaiHavenEpisodeSource.isEpisode(url)) ||
            // TaiAV's exact selected movie publishes a short-lived
            // first-party /api/getmovie HLS. Prepare Python BEFORE asking
            // the publisher to sign that stream, not after it has started
            // expiring. Do not prewarm on its listings or previewvideo.
            (host=="taiav.com" && VideoSiteProfiles.isVideoPage(url))
    }

    fun isSelectedManifest(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull() ?: return false
        return uri.scheme=="https" && !uri.host.isNullOrBlank() &&
            uri.userInfo==null && uri.fragment==null && uri.port==-1 &&
            uri.path.orEmpty().matches(Regex("""(?i)^/[^?#]{5,2000}\.(?:m3u8|mpd)$"""))
    }
}
