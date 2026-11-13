package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles

/** Exact per-item tap routing; not site-wide backfill or automatic download.
 * Page detection is based on publisher route, not an unreliable index kind.
 */
internal object SelectedVideoRouter {
    fun isSelectedVideo(area:String,url:String,kind:String?=null):Boolean =
        area in setOf("ADULT","HENTAI","VIDEOS") &&
            SourceIndexParser.safe(url) &&
            (VideoSiteProfiles.isVideoPage(url) || kind=="video")
}
