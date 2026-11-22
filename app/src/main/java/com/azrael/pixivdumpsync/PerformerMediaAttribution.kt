package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import com.asterion.videoflow.WebDiscovery
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Only actual media linked within a CONFIRMED performer profile's own video
 * grid may be automatically queued. Name matches, related lists, generic
 * search results, and paid/foreign page links remain manual review.
 */
internal object PerformerMediaAttribution {
    internal data class ProfileMedia(val url:String,val title:String,
                                     val thumbnail:String?)
    private const val OWN_VIDEO_AREAS =
        "main .videos, main .video-list, main .video-grid, main .videoUList, " +
        "main .video-listing, main .videos-list, main .video-thumbs, " +
        "main #videos, #profile-videos, #model-videos, .profile-videos, " +
        ".model-videos, .pornstar-videos, [data-creator-videos], [data-model-videos]"
    private const val UNRELATED =
        "aside,nav,header,footer,.related,.related-videos,.recommendations," +
        ".recommended,.suggested,.sponsored,.advertisement,.advertisements," +
        ".ads,[data-ad],.sidebar,.trending"

    fun attributableMedia(name:String,profileUrl:String,
                          doc:Document):List<ProfileMedia> {
        if(!PerformerIdentity.matchesEvidence(name,profileUrl,doc))return emptyList()
        val confirmed=LinkedHashMap<String,ProfileMedia>()
        for(area in doc.select(OWN_VIDEO_AREAS)) {
            if(area.closest(UNRELATED)!=null)continue
            for(anchor in area.select("a[href]")) {
                if(anchor.closest(UNRELATED)!=null)continue
                val url=WebDiscovery.normalize(anchor.absUrl("href"))?:continue
                if(!VideoSiteProfiles.sameProvider(profileUrl,url) ||
                    !VideoSiteProfiles.isVideoPage(url))continue
                val text=anchor.text().trim()
                val poster=anchor.selectFirst(
                    "img[data-original],img[data-src],img[src]")
                val nameEvidence=poster?.attr("alt").orEmpty().trim()
                val title=text.ifBlank{nameEvidence}
                    .ifBlank{url.substringAfterLast('/').take(100)}
                    .take(200)
                val thumb=poster?.let{image->
                    val raw=listOf("data-original","data-src","src")
                        .firstNotNullOfOrNull{key->
                            image.attr(key).trim().takeIf{it.isNotBlank()}
                        }.orEmpty()
                    runCatching{URI(profileUrl).resolve(raw).toString()}
                        .getOrNull()?.takeIf{
                            it.startsWith("https://") &&
                                SearchRanking.validUrl(it)
                        }
                }
                confirmed.putIfAbsent(SearchRanking.canonicalKey(url),
                    ProfileMedia(url,title,thumb))
            }
        }
        return confirmed.values.toList()
    }

    fun attributableUrls(name:String,profileUrl:String,
                         doc:Document):Set<String> =
        attributableMedia(name,profileUrl,doc).mapTo(LinkedHashSet()){
            SearchRanking.canonicalKey(it.url)
        }
}
