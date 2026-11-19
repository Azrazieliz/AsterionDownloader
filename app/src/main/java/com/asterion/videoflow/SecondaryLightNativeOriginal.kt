package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Native-only second process: selected first-party publisher originals from
 * static HTML, without starting a SECOND embedded Python/yt-dlp or WebView.
 *
 * The original two-process VideoFlow and VideoDb lease remain authoritative.
 * Failure to obtain ONE attributable direct original releases the lease to
 * the primary engine (no blind retries, no unrelated media selection).
 */
internal object SecondaryLightNativeOriginal {
    data class Candidate(val url:String,val referer:String,
                         val verifiedFirstPartyMp4:Boolean)
    fun eligible(selected:String):Boolean {
        val host=runCatching{
            URI(selected).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")
        }.getOrDefault("")
        return when(host){
            "jav.guru"->JavGuruJkNativeHls.selectedArticleForNative(selected)
            "rule34video.com"->Rule34VideoOriginalSource.videoId(selected)!=null
            "hentaibros.net"->VideoSiteProfiles.isVideoPage(selected)
            // This publisher directly advertises quality-labelled MP4 files
            // bound to the selected numeric video ID. The second isolated
            // native agent needs neither Python nor a heavyweight WebView.
            "freepornvideos.xxx"->FreePornVideosOriginalSource.selectedPage(selected)
            else->false
        }
    }
    /** Keep the native transfer's validated first-party MIME path when the
     * publisher's selected MP4 URL ends in a slash, rather than dispatching
     * the entire publisher page to an unsupported Python extractor.
     */
    fun verifiedFirstPartyMp4(selected:String,media:String):Boolean =
        Rule34VideoOriginalSource.firstPartyDownload(selected,media) ||
        FreePornVideosOriginalSource.selectedFileQuality(selected,media)!=null

    fun resolve(context:Context,selected:String):Candidate? {
        if(!eligible(selected))return null
        val connection=Jsoup.connect(selected)
            .userAgent(NativeMediaDownloader.USER_AGENT)
            .ignoreHttpErrors(true).followRedirects(false)
            .timeout(14_000).maxBodySize(2_000_000)
            .apply {
                NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                SiteSession.cookie(context,selected)?.takeIf{it.isNotBlank()}
                    ?.let{header("Cookie",it)}
            }.execute()
        if(connection.statusCode() !in 200..299 ||
            !connection.contentType().contains("html",true))return null
        val doc=Jsoup.parse(connection.body(),selected)
        val source=when(
            URI(selected).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")){
            "jav.guru" -> {
                // Reuse the tested same-selected shortcode -> first-party
                // relay -> Maxstream source resolver, WITHOUT the browser
                // fallback or any SB/DD/LU/Python candidate. A missing JK
                // source is deferred to the primary rather than substituted.
                SiteSpecificOriginalSources.javGuru(
                    context,doc,selected,nativeJkOnly=true)
                    .singleOrNull()?.takeIf{
                        JavGuruJkNativeHls.eligible(selected,it.url,it.referer)
                    }?.let{Candidate(it.url,it.referer,false)}
            }
            "rule34video.com" -> {
                // The selected publisher's quality-labelled /get_file link
                // is stronger identity evidence than unrelated HTML5 adverts.
                Rule34VideoOriginalSource.parse(connection.body(),selected)
                    .firstOrNull()?.url
                    ?.takeIf{
                        NativeMediaDownloader.isNativeEligible(selected,it,true)
                    }?.let{Candidate(it,selected,true)}
            }
            "hentaibros.net" -> {
                val sources=SiteSpecificOriginalSources.selectedStatic(
                    doc,selected,"hentaibros.net")
                sources.singleOrNull()?.takeIf{
                    NativeMediaDownloader.isNativeEligible(
                        selected,it.url)
                }?.let{Candidate(it.url,it.referer,false)}
            }
            "freepornvideos.xxx" -> {
                // Never use the site's unrelated recommendation videos.
                // selectedOriginal matches the requested /videos/<id> to
                // the native /get_file/.../<same-id>_<quality>m.mp4/ route.
                FreePornVideosOriginalSource.selectedOriginal(doc,selected)
                    ?.takeIf{
                        FreePornVideosOriginalSource.selectedFileQuality(
                            selected,it)!=null &&
                        NativeMediaDownloader.isNativeEligible(
                            selected,it,true)
                    }?.let{Candidate(it,selected,true)}
            }
            else -> null
        }
        return source
    }
}

/** Native-only admission is a scheduling hint, not an original-file failure. */
internal class SecondaryNativeDeferred:Exception(
    "Selected source needs the primary video extractor")
