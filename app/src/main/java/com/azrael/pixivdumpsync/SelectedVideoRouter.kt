package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import java.net.URI

/** Only an explicit selected page on an approved provider enters VideoFlow.
 * Index 'kind' is unreliable: a recognized video page may be labeled image.
 * Never submit a provider home/category listing as a video even if indexed
 * kind says video.
 */
internal object SelectedVideoRouter {
    fun isSelectedVideo(area:String,url:String,kind:String?=null):Boolean {
        val validArea=area.uppercase() in setOf(
            "ADULT","HENTAI","VIDEOS","VIDEO","PORN")
        if(!SourceIndexParser.safe(url) || !validArea)return false
        // Legacy indexed rows may be labeled adult/hentai rather than video.
        // First-party selected-page recognition is stronger than the old
        // SourceIndex kind, but NEVER overrides a different content module.
        if(VideoSiteProfiles.isVideoPage(url))return true
        if(kind?.lowercase() !in setOf(
                "video","adult","hentai"))return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val path=uri.path.orEmpty().trim('/')
        if(path.isBlank())return false
        val parts=path.lowercase().split('/')
        if(parts.any{it in setOf("category","categories","tag","tags",
                "models","model","actors","actor","search","page","pages",
                "home","latest","popular","trending","privacy","login",
                "register","terms","about","contact","explore",
                "channels","channel","genres","genre","coming-soon",
                "uncensored","engsub","pornstar","pornstars",
                "torrents","downloads","trending-videos")})return false
        return VideoSiteProfiles.coveredHosts.any{known->
            VideoSiteProfiles.sameProvider("https://$known/",url)
        }
    }
}
