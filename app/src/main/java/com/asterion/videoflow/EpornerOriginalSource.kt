package com.asterion.videoflow

import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI

/**
 * Exact first-party Eporner source adapter. The official video-ID API attests
 * the full duration of the published item, independently of yt-dlp's player
 * output. The official embed endpoint provides an alternate player route for
 * users with their own explicitly connected website session.
 *
 * No third-party mirror, proxy, bypass, fabricated media address, or silent
 * age-verification workaround is used.
 */
internal object EpornerOriginalSource {
    private val path=Regex(
        """^/(?:video-|hd-porn/|embed/)([A-Za-z0-9]{6,20})(?:/|$)""")
    fun videoId(url:String):String?{
        val uri=runCatching{URI(url)}.getOrNull()?:return null
        if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
            !VideoSiteProfiles.sameProvider(url,"https://www.eporner.com/"))
            return null
        return path.find(uri.path.orEmpty())?.groupValues?.get(1)
    }
    fun officialEmbed(url:String):String?=videoId(url)?.let{
        "https://www.eporner.com/embed/$it/"
    }
    /** Canonical publisher identity deduplicates HD, title and embed aliases. */
    fun canonicalUrl(url:String):String?=videoId(url)?.let{
        "https://www.eporner.com/video-"+it+"/"
    }
    fun videoCandidates(url:String,sessionConnected:Boolean):List<String>{
        val embed=officialEmbed(url)
        if(embed==null)return listOf(url)+SourceDiagnostics.alternateHosts(url)
        // The publisher's own /embed/<id> endpoint is a PUBLIC player, not
        // an account bypass. Upstream yt-dlp's Eporner watch-page hash
        // extraction is broken for some videos; the public embed is a known
        // supported alternative even when no login is connected. Cookies are
        // still used only if the owner has actually connected the site.
        return listOf(embed,url).distinct()
    }
    /** Returns null rather than trusting unverified runtime if the provider's
     * first-party identity and duration cannot be independently checked.
     */
    /**
     * Eporner's own video player exposes a 32-character hash. Its publisher
     * xhr/video/<id> JSON supplies the actual MP4/HLS source variants.
     * Blindly feeding the HTML page to generic yt-dlp fails when its old
     * hash/JS parser no longer matches the publisher's player.
     */
    internal fun encodePublishedHash(value:String):String? {
        if(!value.matches(Regex("""[0-9a-fA-F]{32}""")))return null
        return (0 until 4).joinToString("") { n ->
            java.lang.Long.toString(
                value.substring(n*8,(n+1)*8).toLong(16),36)
        }
    }
    internal fun publishedHash(html:String):String? =
        Regex("""(?i)\\bhash\\s*[:=]\\s*['"]([0-9a-f]{32})['"]""")
            .find(html)?.groupValues?.get(1)

    internal fun sourceUrls(rawJson:String):List<String> {
        val parsed=runCatching{JSONObject(rawJson)}.getOrNull()?:return emptyList()
        if(!parsed.optBoolean("available",true))return emptyList()
        val sources=parsed.optJSONObject("sources")?:return emptyList()
        val found=LinkedHashSet<String>()
        val kinds=sources.keys()
        while(kinds.hasNext()) {
            val group=sources.optJSONObject(kinds.next())?:continue
            val formats=group.keys()
            while(formats.hasNext()){
                val format=group.optJSONObject(formats.next())?:continue
                val src=format.optString("src")
                val uri=runCatching{URI(src)}.getOrNull()?:continue
                if(uri.scheme!="https" || uri.host==null ||
                    uri.userInfo!=null || uri.port!=-1 ||
                    !WebDiscovery.isUrl(src) ||
                    FullLengthVideoPolicy.isExplicitPreview("",src))
                    continue
                if(NativeMediaDownloader.isDirectMediaUrl(src) ||
                    uri.path.orEmpty().matches(Regex("""(?i)^.*\\.(?:m3u8|mpd)$""")))
                    found+=src
            }
        }
        return found.toList()
    }

    /** Fetch only the selected first-party publisher's verified player API. */
    fun publishedSources(page:String,doc:org.jsoup.nodes.Document,
                         context:android.content.Context):List<String>{
        val id=videoId(page)?:return emptyList()
        if(WebDiscovery.epornerAccessGate(doc))return emptyList()
        val token=encodePublishedHash(publishedHash(doc.outerHtml()).orEmpty())
            ?:return emptyList()
        val xhr="https://www.eporner.com/xhr/video/$id?hash=$token&"+
            "device=generic&domain=www.eporner.com&fallback=false"
        val response=Jsoup.connect(xhr)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
            .header("Accept","application/json, text/javascript")
            .header("X-Requested-With","XMLHttpRequest")
            .header("Referer",page)
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(false)
            .timeout(15_000)
            .maxBodySize(1_800_000)
            .apply {
                NetworkRoute.javaProxy(context,xhr)?.let{proxy(it)}
                com.azrael.galleryflow.SiteSession.cookie(context,page)
                    ?.takeIf{it.isNotBlank()}?.let{header("Cookie",it)}
            }.execute()
        if(response.statusCode() !in 200..299)return emptyList()
        return sourceUrls(response.body())
    }

    fun officialDuration(url:String):Long? {
        val id=videoId(url)?:return null
        val api="https://www.eporner.com/api/v2/video/id/?id="+id
        return runCatching {
            val response=Jsoup.connect(api)
                .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .followRedirects(false)
                .timeout(12_000)
                .maxBodySize(800_000)
                .execute()
            require(response.statusCode() in 200..299){
                "Official Eporner video API unavailable"
            }
            val parsed=JSONObject(response.body())
            require(parsed.optString("id")==id &&
                videoId(parsed.optString("url"))==id){
                "Official Eporner video identity mismatch"
            }
            parsed.optLong("length_sec",0L).takeIf{it in 1..(24L*60*60)}
        }.getOrNull()
    }
}
