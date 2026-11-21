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
        // Poster lookup must use the SAME publisher network route as that
        // user's selected video. Direct Jsoup ignored the per-publisher
        // authorized proxy and returned empty posters even during a working
        // video transfer. This does not change any download engine.
        com.asterion.videoflow.NetworkRoute.javaProxy(context,page)
            ?.let{connection.proxy(it)}
        SiteSession.cookie(context,page)?.takeIf{it.isNotBlank()}
            ?.let{connection.header("Cookie",it)}
        val response=connection.execute()
        val mime=response.contentType().orEmpty().lowercase(java.util.Locale.ROOT)
        val direct=if(response.statusCode() in 200..299 &&
            (mime.startsWith("text/html") ||
             mime.startsWith("application/xhtml+xml")))
            fromHtml(response.body(),response.url().toString())
        else null
        if(direct!=null)return direct
        // Publisher watch/player routes can work through the already
        // authorized Android browser while raw Jsoup returns 403 or a
        // static JS shell with no poster. Reuse the SAME public/connected
        // browser session as selected media, but read only first-party
        // page metadata. An access gate is never bypassed and never
        // interpreted as a successful thumbnail.
        val host=runCatching{URI(page).host.orEmpty().lowercase()
            .removePrefix("www.")}.getOrDefault("")
        val eligible=host in setOf(
            "pornhub.com","fullporner.com","jav.guru","livecamrips.to",
            "eporner.com","pimpbunny.com","pornavhd.com","xchina.co") &&
            (BrowserPageFetch.publicEligible(page) ||
             SiteSession.bound(context,page))
        if(!eligible || response.statusCode() !in
                setOf(200,401,403))return null
        return runCatching{
            fromHtml(BrowserPageFetch.html(context,page),page)
        }.getOrNull()
    }
}
