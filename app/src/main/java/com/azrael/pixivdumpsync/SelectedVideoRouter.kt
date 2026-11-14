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
        if(area.uppercase() !in setOf("ADULT","HENTAI","VIDEOS") ||
            !SourceIndexParser.safe(url))return false
        if(VideoSiteProfiles.isVideoPage(url))return true
        if(!kind.equals("video",true))return false
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val path=uri.path.orEmpty().trim('/')
        if(path.isBlank())return false
        val parts=path.lowercase().split('/')
        if(parts.any{it in setOf("category","categories","tag","tags",
                "models","model","actors","actor","search","page","pages",
                "home","latest","popular","trending","privacy","login",
                "register","terms","about","contact","explore")})return false
        return VideoSiteProfiles.coveredHosts.any{known->
            VideoSiteProfiles.sameProvider("https://$known/",url)
        }
    }
}
