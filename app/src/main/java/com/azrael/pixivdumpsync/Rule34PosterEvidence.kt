package com.azrael.pixivdumpsync

import android.content.Context
import com.asterion.videoflow.NetworkRoute
import com.asterion.videoflow.Rule34VideoOriginalSource
import com.azrael.galleryflow.SiteSession
import org.jsoup.Jsoup
import java.net.URI

/** Selected-page poster only. Never manufacture an image URL from a video ID. */
internal object Rule34PosterEvidence {
    /** Rule34Video's current watch page omits og:image and has no static
     * <video poster>. It publishes the *selected* video ID and exact
     * preview_url inside the first-party KVS flashvars player script.
     * Only a preview URL containing that SAME selected ID is accepted,
     * never screenshots from the sidebar/related videos.
     */
    internal fun selectedFlashvarsPoster(page:String,html:String):String? {
        val selectedId=Rule34VideoOriginalSource.videoId(page)?:return null
        val doc=Jsoup.parse(html,page)
        for(script in doc.select("script:not([src])").take(65)){
            val source=script.data().ifBlank{script.html()}
            val start=Regex("""\b(?:var|let)\s+flashvars\s*=\s*\{""")
                .find(source)?.range?.first?:continue
            val player=source.substring(start).take(40_000)
                .substringBefore("kt_player(")
            val declared=Regex("""\bvideo_id\s*:\s*['"]([0-9]{1,12})['"]""")
                .find(player)?.groupValues?.get(1)
            if(declared!=selectedId)continue
            val raw=Regex("""\bpreview_url\s*:\s*['"]([^'"]{12,1000})['"]""")
                .find(player)?.groupValues?.get(1)?:continue
            val candidate=runCatching{
                URI(page).resolve(raw.replace("&amp;","&").trim())
            }.getOrNull()?:continue
            if(candidate.scheme!="https" || candidate.userInfo!=null ||
                candidate.port!=-1 || candidate.fragment!=null ||
                candidate.host.orEmpty().lowercase().removePrefix("www.")!=
                    "rule34video.com")continue
            val path=Regex(
                """^/contents/videos_screenshots/[0-9]{1,12}/$selectedId/"""+
                """[A-Za-z0-9_.-]{3,180}\.(?:jpe?g|png|webp)$""",
                RegexOption.IGNORE_CASE)
            if(path.matches(candidate.path.orEmpty()))
                return candidate.toASCIIString()
        }
        return null
    }

    internal fun selectedPoster(page:String,html:String):String? {
        if(Rule34VideoOriginalSource.videoId(page)==null)return null
        // Source order matters: the selected player's own preview beats
        // generic site images or unrelated recommendation metadata.
        selectedFlashvarsPoster(page,html)?.let{return it}
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
            .userAgent(com.asterion.videoflow.NativeMediaDownloader.USER_AGENT)
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
