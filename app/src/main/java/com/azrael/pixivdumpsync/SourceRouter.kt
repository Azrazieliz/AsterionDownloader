package com.azrael.pixivdumpsync

import java.net.URI
import java.util.Locale

/** One intake for Asterion Downloader. Engines remain internal implementation details. */
enum class DownloadKind { PIXIV, X, VIDEO, COMIC, GALLERY }
object SourceRouter {
    fun classify(raw:String):DownloadKind {
        // This enum has no REJECTED state. The entry-point already rejects
        // retired hosts; classify them as non-video to prevent VideoFlow
        // admission from generic /video paths or old external SHARE links.
        if(ProviderRetirement.isRetired(raw))return DownloadKind.GALLERY
        val uri=runCatching { URI(raw.trim()) }.getOrNull()
            ?:return DownloadKind.GALLERY
        val host=uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        if(host=="pixiv.net"||host.endsWith(".pixiv.net")||
           host=="pximg.net"||host.endsWith(".pximg.net"))return DownloadKind.PIXIV
        if(host=="x.com"||host=="twitter.com"||host=="mobile.twitter.com"||
           host=="fxtwitter.com"||host=="vxtwitter.com")return DownloadKind.X
        if(NhentaiChapters.isGallery(raw))return DownloadKind.COMIC
        if(host in setOf("toonily.com","mangaread.org") &&
            Regex("/(serie|series|manga|manhwa)/[^/]+").containsMatchIn(path))
            return DownloadKind.COMIC
        if(Regex("(?i)(/manga/|/manhwa/|/series/|/serie/|/chapter[-/]|/chapitre[-/])")
            .containsMatchIn(path))return DownloadKind.COMIC
        // Many supported original-video pages use native publisher routes
        // such as JAV Guru /123456/title/ and JAVIDEO /en/fc2ppv-12345.
        // A generic /video or .mp4 heuristic
        // wrongly sends them to gallery sync when shared from Android,
        // silently bypassing the real VideoFlow engine.
        if(com.asterion.videoflow.VideoSiteProfiles.isVideoPage(raw))
            return DownloadKind.VIDEO
        if(Regex("(?i)\\.(mp4|webm|m3u8|mpd|mov)([?#]|$)").containsMatchIn(raw)||
           Regex("(?i)(/watch|/video|/shorts/|/reel/|/clip/|/post/)")
            .containsMatchIn(path)||
           host in setOf("youtube.com","youtu.be","vimeo.com","tiktok.com",
               "dailymotion.com","twitch.tv","instagram.com"))return DownloadKind.VIDEO
        return DownloadKind.GALLERY
    }
    fun xPost(raw:String):Boolean {
        val uri=runCatching{URI(raw)}.getOrNull()?:return false
        return classify(raw)==DownloadKind.X && Regex("/status/\\d+").containsMatchIn(uri.path.orEmpty())
    }
    fun pixivArtistId(raw:String):String? =
        Regex("pixiv\\.net/(?:[a-z]{2}/)?users/(\\d+)",RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)
}
