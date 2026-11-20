package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/**
 * Existing TaiAV full-video endpoint, established from actual publisher page
 * and Windows PC selected-player HTTP evidence on 2026-11-20:
 * GET /api/getmovie?type=<published .hdselect option>&id=<selected hex24>
 * -> JSON {"m3u8":"<selected HLS reference>"}.
 *
 * The same site independently serves /previewvideo?id=<id>, which is NEVER
 * an original source. Do not inspect ad iframes or guessed player mirrors.
 */
internal object TaiAvOriginalSource {
    private val movie=Regex(
        """^/(?:cn|tw|en)/movie/([a-f0-9]{24})/?$""",
        RegexOption.IGNORE_CASE)
    private val publishedId=Regex(
        """(?i)\bvar\s+id\s*=\s*["']([a-f0-9]{24})["']""")
    private val quality=Regex("""[A-Za-z0-9_-]{1,20}""")
    private val badMedia=Regex(
        """(?i)(?:^|[/_-])(?:preview(?:video)?|trailer|teaser|sample|thumb(?:nail)?)(?:[/_.-]|$)""")

    data class Request(val selected:String,val endpoint:String,
                       val quality:String,val movieId:String)

    fun selectedRequest(article:String,html:String):List<Request> {
        val uri=runCatching{URI(article)}.getOrNull()?:return emptyList()
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            uri.rawFragment!=null ||
            uri.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")!="taiav.com")return emptyList()
        val id=movie.matchEntire(uri.path.orEmpty())?.groupValues?.get(1)
            ?:return emptyList()
        if(!VideoSiteProfiles.isVideoPage(article))return emptyList()
        // Bind selected article DOM to that exact publisher's AJAX script.
        // Generic site shell, homepage or unrelated embedded preview is not
        // proof that the current movie supports full playback.
        val scriptId=publishedId.find(html)?.groupValues?.get(1)
        if(scriptId==null || !scriptId.equals(id,true) ||
            !html.contains("/api/getmovie") ||
            !html.contains("data.m3u8"))return emptyList()
        val doc=Jsoup.parse(html,article)
        val ordered=doc.select(".hdselect option[value]")
            .map{it.attr("value").trim()}
            .filter{quality.matches(it)}
            .distinct().take(4)
        return ordered.map{setting->
            Request(article,"https://taiav.com/api/getmovie?type="+
                URLEncoder.encode(setting,"UTF-8")+"&id="+id,
                setting,id)
        }
    }

    fun verifiedHls(request:Request,json:String):String? {
        val data=runCatching{JSONObject(json)}.getOrNull()?:return null
        // The publisher's public player explicitly identifies this field.
        // Its similarly named /previewvideo endpoint is categorically banned.
        val raw=data.optString("m3u8").trim()
        if(raw.isBlank() || raw.length>2500 ||
            raw.startsWith("javascript:",true) ||
            raw.startsWith("data:",true) ||
            raw.startsWith("blob:",true))return null
        val link=runCatching{
            URI(request.selected).resolve(URI(raw))
        }.getOrNull()?:return null
        val host=link.host.orEmpty().lowercase(Locale.ROOT)
        // Observed /api/getmovie on the exact selected TaiAV movie returns
        // a first-party relative HLS playlist; no off-site/preview mirror
        // is authenticated by this JSON field.
        if(host!="taiav.com")return null
        if(link.scheme!="https" || host.isBlank() ||
            host=="localhost" || host=="127.0.0.1" ||
            link.userInfo!=null || link.port!=-1 ||
            link.rawFragment!=null ||
            !link.path.orEmpty().endsWith(".m3u8",true) ||
            badMedia.containsMatchIn(link.path.orEmpty()))return null
        return link.toASCIIString()
    }

    fun resolve(context:Context,article:String,html:String):String? {
        val candidates=selectedRequest(article,html)
        for(request in candidates){
            val body=runCatching {
                val response=Jsoup.connect(request.endpoint)
                    .userAgent(NativeMediaDownloader.USER_AGENT)
                    .header("Referer",request.selected)
                    .header("Accept","application/json")
                    .header("X-Requested-With","XMLHttpRequest")
                    .ignoreHttpErrors(true)
                    .followRedirects(false)
                    .timeout(13_000).maxBodySize(150_000)
                    .apply {
                        NetworkRoute.javaProxyForSelected(context,article,
                            request.endpoint)?.let{proxy(it)}
                        SiteSession.cookie(context,article)?.takeIf{
                            it.isNotBlank()
                        }?.let{header("Cookie",it)}
                    }.execute()
                // The actual player contract is its small JSON body,
                // not a required Content-Type header: some mirrors return
                // application/octet-stream for an otherwise valid API reply.
                if(response.statusCode()!=200)null
                else response.body()
            }.getOrNull()?:continue
            verifiedHls(request,body)?.let{return it}
        }
        return null
    }
}
