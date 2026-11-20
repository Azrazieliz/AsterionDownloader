package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import com.asterion.videoflow.WebDiscovery
import org.jsoup.nodes.Document

/**
 * A matching video title, search result, or related-video carousel is not
 * evidence of performer participation. Automatic performer backfill is only
 * allowed for media linked inside the confirmed person's own video listing.
 * Everything else stays a manual-review search candidate.
 */
internal object PerformerMediaAttribution {
    private const val OWN_VIDEO_AREAS =
        "main .videos, main .video-list, main .video-grid, main .videoUList, " +
        "main #videos, #profile-videos, #model-videos, .profile-videos, " +
        ".model-videos, .pornstar-videos, [data-creator-videos], [data-model-videos]"
    private const val UNRELATED =
        "aside,nav,header,footer,.related,.related-videos,.recommendations," +
        ".recommended,.suggested,.sponsored,.advertisement,.advertisements," +
        ".ads,[data-ad],.sidebar,.trending"

    fun attributableUrls(name:String, profileUrl:String, doc:Document):Set<String> {
        // Path alone is not a verified identity; require the live person's
        // first-party heading or structured-data identity on this very page.
        if(!PerformerIdentity.matchesEvidence(name,profileUrl,doc))return emptySet()
        val confirmed=LinkedHashSet<String>()
        for(area in doc.select(OWN_VIDEO_AREAS)) {
            if(area.closest(UNRELATED)!=null)continue
            for(anchor in area.select("a[href]")) {
                if(anchor.closest(UNRELATED)!=null)continue
                val url=WebDiscovery.normalize(anchor.absUrl("href"))?:continue
                if(!VideoSiteProfiles.sameProvider(profileUrl,url) ||
                    !VideoSiteProfiles.isVideoPage(url))continue
                confirmed.add(SearchRanking.canonicalKey(url))
            }
        }
        return confirmed
    }
}
