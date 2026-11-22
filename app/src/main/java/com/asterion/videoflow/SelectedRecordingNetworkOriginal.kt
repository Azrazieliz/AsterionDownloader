package com.asterion.videoflow

import java.net.URI
import java.util.Locale

/**
 * PC evidence 22 Nov 2026: the exact selected PornAVHD playerrecord iframe
 * loads an MP4 from z6v2p9a8.bkcdn.net over browser Fetch (HTTP 200) and
 * Media (HTTP 206), but exposes no HTML5 currentSrc/MP4 tag in static HTML.
 *
 * This permits observing ONLY an MP4 request from the already publisher-
 * attested video.playerrecord.cfd/e/<id> WebView. It does not fetch media,
 * grant original status, infer a URL or inspect other frames. Native transfer
 * must independently validate HTTP media MIME, full received length and
 * final playable duration. A 3-second preview remains HELD.
 */
internal object SelectedRecordingNetworkOriginal {
    private val cdnHost=Regex("""^[a-z0-9-]{4,48}\.bkcdn\.net$""")
    private val mediaPath=Regex("""(?i)^/[A-Za-z0-9_.%/-]{5,1500}\.mp4$""")
    private val framePath=Regex("""^/e/[A-Za-z0-9_-]{8,100}/?$""")
    private val unsafeNames=Regex("""(?i)(?:^|[-_/])(preview|trailer|teaser|sample)(?:[-_./]|$)""")
    private fun parsed(raw:String):URI?=runCatching{URI(raw)}.getOrNull()
        ?.takeIf{uri ->
            uri.scheme=="https" && uri.userInfo==null && uri.port==-1 &&
                uri.rawFragment==null
        }

    fun isAttestedPlayer(article:String,player:String):Boolean {
        if(NetworkRoute.siteKey(article)!="pornavhd.com" ||
            !VideoSiteProfiles.isVideoPage(article) ||
            !SiteSpecificOriginalSources.selectedExtractorFrame(article,player))
            return false
        val uri=parsed(player)?:return false
        return uri.host.orEmpty().lowercase(Locale.ROOT)=="video.playerrecord.cfd" &&
            uri.rawQuery.isNullOrBlank() &&
            framePath.matches(uri.path.orEmpty())
    }

    fun approvedObservedRequest(
        article:String,player:String,media:String,
        isForMainFrame:Boolean,requestReferer:String?
    ):Boolean {
        if(isForMainFrame || !isAttestedPlayer(article,player))return false
        val source=parsed(media)?:return false
        if(!cdnHost.matches(source.host.orEmpty().lowercase(Locale.ROOT)) ||
            !source.rawQuery.isNullOrBlank() ||
            !mediaPath.matches(source.path.orEmpty()) ||
            unsafeNames.containsMatchIn(source.path.orEmpty()))
            return false
        if(!requestReferer.isNullOrBlank()){
            val referer=parsed(requestReferer)?:return false
            if(referer.host.orEmpty().lowercase(Locale.ROOT)!="video.playerrecord.cfd" ||
                (!referer.path.isNullOrBlank() && referer.path!="/" &&
                    referer.path!=parsed(player)?.path))
                return false
        }
        return true
    }
}
