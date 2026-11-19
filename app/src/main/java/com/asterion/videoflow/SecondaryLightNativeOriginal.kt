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
            "rule34video.com"->Rule34VideoOriginalSource.videoId(selected)!=null
            "hentaibros.net"->VideoSiteProfiles.isVideoPage(selected)
            else->false
        }
    }
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
            else -> null
        }
        return source
    }
}

/** Native-only admission is a scheduling hint, not an original-file failure. */
internal class SecondaryNativeDeferred:Exception(
    "Selected source needs the primary video extractor")
