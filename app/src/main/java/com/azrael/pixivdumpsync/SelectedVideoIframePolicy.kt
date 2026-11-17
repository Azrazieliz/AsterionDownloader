package com.azrael.pixivdumpsync

import com.asterion.videoflow.ArchivebateSelectedMedia
import com.asterion.videoflow.SiteSpecificOriginalSources
import com.asterion.videoflow.VideoSiteProfiles
import java.net.URI
import java.util.Locale

/**
 * An Android WebView navigation to an external SELECTED player is a
 * subframe, not a top-level site escape. The old all-cross-host filter
 * blocked precisely the VOE/Streamtape/NHPlayer/Mixdrop iframe needed by
 * the original resolvers while leaving the parent page loaded.
 *
 * Admit only source-verified player routes for the currently selected page.
 * Main-frame navigation remains on the original site.
 */
internal object SelectedVideoIframePolicy {
    private fun host(uri:URI)=uri.host.orEmpty()
        .lowercase(Locale.ROOT).removePrefix("www.")
    fun allow(parent:String,child:String,mainFrame:Boolean):Boolean {
        if(child=="about:blank" && !mainFrame)return true
        val source=runCatching{URI(parent)}.getOrNull()?:return false
        val target=runCatching{URI(child)}.getOrNull()?:return false
        if(source.scheme!="https" || target.scheme!="https" ||
            source.userInfo!=null || target.userInfo!=null ||
            source.port!=-1 || target.port!=-1 ||
            target.fragment!=null || target.host.isNullOrBlank())return false
        if(host(source)==host(target))return true
        if(mainFrame)return false
        if(ArchivebateSelectedMedia.isSelectedEmbed(parent,child))return true
        if(SiteSpecificOriginalSources.selectedExtractorFrame(parent,child))return true
        if(host(source)=="uncensoredhentai.xxx" &&
            VideoSiteProfiles.isVideoPage(parent) &&
            host(target)=="nhplayer.com" &&
            target.rawQuery.isNullOrBlank() &&
            Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                .matches(target.path.orEmpty()))return true
        if(host(source)=="roshy.tv" &&
            VideoSiteProfiles.isVideoPage(parent) &&
            host(target)=="kamehaus.net" &&
            target.rawQuery.isNullOrBlank() &&
            Regex("""^/e/[A-Za-z0-9_-]{6,80}/?$""")
                .matches(target.path.orEmpty()))return true
        return false
    }
}
