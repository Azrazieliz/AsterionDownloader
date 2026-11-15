package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.json.JSONObject
import org.jsoup.Connection
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/**
 * Selected first-party 3DHentai player: the post HTML contains NO media src.
 * Its published JS POSTs post_id+the current page nonce to WordPress action
 * tdx_get_signed_playback_url, then assigns the returned URL to HTML5 video.
 * Unlike scraping preview cards, this ties the media to the selected post.
 *
 * Observed on real public post 7804 (2026-11-15):
 * - official AJAX 200, success=true, data.url
 * - CDN host cdn.3dhentaix.com, path /index.php, signed query
 * - exact CDN response 206 video/mp4, Content-Range and ftyp bytes.
 * The signed URL MUST be obtained fresh for each attempt.
 */
internal object ThreeDHentaiSignedOriginal {
    internal data class Published(val postId:Long,val nonce:String)

    fun isSelectedArticle(page:String):Boolean {
        val u=runCatching{URI(page)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==
                "3dhentaix.com" && VideoSiteProfiles.isVideoPage(page)
    }

    fun isSignedMediaUrl(media:String):Boolean{
        val u=runCatching{URI(media)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.fragment==null && u.host.orEmpty().lowercase(Locale.ROOT)==
                "cdn.3dhentaix.com" &&
            u.path=="/index.php" && u.rawQuery.orEmpty().length in 8..2048
    }

    fun firstPartyFormat(page:String,media:String):Boolean =
        isSelectedArticle(page) && isSignedMediaUrl(media)

    /** One selected article player with one genuine signed playback config. */
    internal fun publishedConfig(doc:Document,selected:String):Published?{
        if(!isSelectedArticle(selected) ||
            doc.select("main .video-player,article .video-player").isEmpty())
            return null
        val pattern=Regex(
            """(?s)\bwindow\.awpSignedPlayback\s*=\s*(\{[^}]{12,1200}\})\s*;""")
        val configs=doc.select("script:not([src])").asSequence()
            .take(90).mapNotNull {script->
                val body=script.data().ifBlank{script.html()}
                pattern.findAll(body).mapNotNull {match->
                    runCatching {JSONObject(match.groupValues[1])}.getOrNull()
                }.toList()
            }.flatten().toList()
        if(configs.size!=1)return null
        val config=configs.single()
        val id=config.optLong("postId",0L)
        val nonce=config.optString("nonce").trim()
        if(id !in 1L..1_000_000_000L ||
            !nonce.matches(Regex("[A-Za-z0-9_-]{6,64}")))return null
        val other=doc.select("article[id^=post-]").filter{
            it.closest("aside,.related,.recommendations,.sidebar")==null
        }.map{it.id()}.distinct()
        // If the selected article explicitly publishes its DOM post ID,
        // it must match the configured playback post ID.
        if(other.isNotEmpty() && "post-$id" !in other)return null
        return Published(id,nonce)
    }

    /** Use only first-party published AJAX, same-origin cookies and referer.
     * Do NOT guess media URLs, forge signatures, or bypass any access gate.
     */
    fun resolve(context:Context,selected:String,doc:Document):String{
        val config=publishedConfig(doc,selected)?:throw IllegalStateException(
            "3DHentai selected post has no unique signed-playback configuration")
        val response=Jsoup.connect(
            "https://3dhentaix.com/wp-admin/admin-ajax.php")
            .userAgent(NativeMediaDownloader.USER_AGENT)
            .method(Connection.Method.POST)
            .data("action","tdx_get_signed_playback_url")
            .data("post_id",config.postId.toString())
            .data("nonce",config.nonce)
            .header("Referer",selected)
            .header("Origin","https://3dhentaix.com")
            .ignoreContentType(true).ignoreHttpErrors(true)
            .followRedirects(false).timeout(15_000)
            .maxBodySize(200_000)
            .apply{
                NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                SiteSession.cookie(context,selected)?.takeIf{it.isNotBlank()}
                    ?.let{header("Cookie",it)}
            }.execute()
        check(response.statusCode() in 200..299){
            "3DHentai official selected playback HTTP "+response.statusCode()
        }
        val body=runCatching{JSONObject(response.body())}.getOrNull()
            ?:throw IllegalStateException(
                "3DHentai official playback did not return JSON")
        check(body.optBoolean("success",false)){
            "3DHentai publisher refused selected signed playback"
        }
        val published=body.optJSONObject("data")?.optString("url").orEmpty()
        check(firstPartyFormat(selected,published)){
            "3DHentai official response did not expose the expected "+
                "selected original on its first-party media CDN"
        }
        return published
    }
}
