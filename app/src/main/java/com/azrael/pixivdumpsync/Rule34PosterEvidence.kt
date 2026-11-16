package com.azrael.pixivdumpsync

import android.content.Context
import com.asterion.videoflow.NetworkRoute
import com.asterion.videoflow.Rule34VideoOriginalSource
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI

/** Selected-page poster only. Never manufacture an image URL from a video ID. */
internal object Rule34PosterEvidence {
    internal fun selectedPoster(page:String,html:String):String? {
        if(Rule34VideoOriginalSource.videoId(page)==null)return null
        val doc=Jsoup.parse(html,page)
        val exact=sequenceOf(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            doc.selectFirst("#player video[poster],.video-player video[poster]")?.attr("poster"),
            doc.selectFirst("link[rel=image_src]")?.attr("href")
        )
        for(raw in exact){
            if(raw.isNullOrBlank() || raw.startsWith("data:",true))continue
            val uri=runCatching{URI(page).resolve(raw.trim())}.getOrNull()?:continue
            if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
                uri.host.isNullOrBlank() || !SearchRanking.validUrl(uri.toString()))
                continue
            val path=uri.path.orEmpty().lowercase()
            if(listOf("logo","favicon","placeholder","default-cover","avatar",
                    "sprite","ads","banner").any{path.contains(it)})continue
            return uri.toString()
        }
        return null
    }

    /** Fetch at most once per visible selected card, off the UI thread.
     * A missing or protected poster is logged, never called a successful tile.
     */
    fun readPoster(context:Context,page:String):String? {
        if(Rule34VideoOriginalSource.videoId(page)==null)return null
        val response=Jsoup.connect(page)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            .header("Accept","text/html,application/xhtml+xml")
            .ignoreHttpErrors(true).followRedirects(true)
            .timeout(7000).maxBodySize(900000)
            .apply{
                NetworkRoute.javaProxy(context,page)?.let{proxy(it)}
                SiteSession.cookie(context,page)?.takeIf{it.isNotBlank()}
                    ?.let{header("Cookie",it)}
            }.execute()
        check(response.statusCode() in 200..299){
            "Selected Rule34Video poster page HTTP "+response.statusCode()
        }
        return selectedPoster(page,response.body())
    }
}
