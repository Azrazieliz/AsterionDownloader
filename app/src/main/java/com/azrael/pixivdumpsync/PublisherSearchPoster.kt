package com.azrael.pixivdumpsync

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI

/**
 * Fetch only first-party declared poster metadata for the EXACT selected
 * video page. No generic stock image, unrelated recommendation or scraper
 * result may be presented as a publisher's video thumbnail.
 */
internal object PublisherSearchPoster {
    fun fromHtml(html:String,page:String):String? {
        val origin=runCatching{URI(page)}.getOrNull()?:return null
        if(origin.scheme!="https" || !SearchRanking.validUrl(page))return null
        val doc=Jsoup.parse(html,page)
        val paths=listOf(
            doc.selectFirst("meta[property=og:image:secure_url]")?.attr("content"),
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("meta[name=twitter:image]")?.attr("content"),
            doc.selectFirst("meta[property=twitter:image]")?.attr("content"),
            doc.selectFirst("video[poster]")?.attr("poster")
        )
        for(candidate in paths){
            if(candidate.isNullOrBlank())continue
            val absolute=runCatching{origin.resolve(candidate.trim())}
                .getOrNull()?:continue
            if(absolute.scheme!="https" ||
                !SearchRanking.validUrl(absolute.toString()) ||
                absolute.userInfo!=null || absolute.rawFragment!=null)continue
            val path=absolute.path.orEmpty().lowercase()
            if(path.endsWith(".svg") || path.endsWith(".html") ||
                path.endsWith(".htm"))continue
            return absolute.toString()
        }
        return null
    }
    fun fetch(context:Context,page:String):String? {
        if(!page.startsWith("https://") ||
            !SearchRanking.validUrl(page) ||
            !(SearchRanking.isVideoUrl(page) ||
                com.asterion.videoflow.VideoSiteProfiles.isVideoPage(page)))return null
        val connection=Jsoup.connect(page).timeout(8_000).maxBodySize(850_000)
            .ignoreHttpErrors(true).followRedirects(false)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
        SiteSession.cookie(context,page)?.takeIf{it.isNotBlank()}
            ?.let{connection.header("Cookie",it)}
        val response=connection.execute()
        if(response.statusCode() !in 200..299)return null
        val mime=response.contentType().orEmpty().lowercase(java.util.Locale.ROOT)
        if(!mime.startsWith("text/html") &&
            !mime.startsWith("application/xhtml+xml"))return null
        return fromHtml(response.body(),response.url().toString())
    }
}
