package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import com.azrael.pixivdumpsync.BrowserPageFetch
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Exact selected-publisher resolvers. Never treat cards, ads or thumbnails as originals. */
internal object SiteSpecificOriginalSources {
    data class Source(val url:String,val referer:String)
    private val owners=setOf("fullporner.com","hqpornerxxx.com","helloporn.com",
        "javideo.net","roshy.tv","mp4hentai.com","hentaibros.net",
        "3dhentaix.com","hentaihaven.xxx","uncensoredhentai.xxx")
    internal fun host(url:String):String=runCatching {
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")
    fun owns(url:String)=host(url) in owners
    /** File-hosting landing pages are not MP4 media even if their title
     * ends in .mp4. Their provider must resolve them separately.
     */
    internal fun isHosterPage(link:String):Boolean =
        host(link) in setOf("k2s.cc","nitroflare.com","uploadgig.com")
    private fun https(raw:String,base:String):String? {
        if(raw.isBlank() || listOf("blob:","javascript:","data:").any{
            raw.startsWith(it,true)})return null
        val u=runCatching{URI(base).resolve(raw.trim().replace("&amp;","&")
            .replace("\\/","/").replace("\\u0026","&"))}.getOrNull()?:return null
        if(u.scheme!="https" || u.host.isNullOrBlank() ||
            u.userInfo!=null || u.port!=-1 ||
            !WebDiscovery.isUrl(u.toString()))return null
        return u.toString().substringBefore('#')
    }
    private fun isMedia(url:String)=runCatching{
        val path=URI(url).path.orEmpty()
        Regex("""(?i)\.(?:mp4|m4v|webm|mov|m3u8|mpd)$""").containsMatchIn(path) &&
            !FullLengthVideoPolicy.isExplicitPreview("",url)
    }.getOrDefault(false)
    private fun runtime(node:Element):Long? =
        node.attr("data-duration").toLongOrNull()
            ?:node.closest("[data-duration]")?.attr("data-duration")?.toLongOrNull()

    private fun mediaElements(doc:Document,referer:String,css:String,
                              minimumRuntime:Boolean=false):List<Source> {
        val found=LinkedHashMap<String,Source>()
        for(element in doc.select(css).take(30)) {
            val raw=element.attr("src").ifBlank{element.attr("data-src")}
                .ifBlank{element.attr("content")}
            val link=https(raw,doc.location())?:continue
            if(!isMedia(link) ||
                (minimumRuntime && (runtime(element)?:0L)<90L))continue
            found.putIfAbsent(link,Source(link,referer))
        }
        return found.values.toList()
    }
    /** Only recognized inline player file/sources assignments, never every
     * URL or MP4 string elsewhere in the selected page.
     */
    private fun jsSources(doc:Document,referer:String):List<Source> {
        val found=LinkedHashMap<String,Source>()
        val patterns=listOf(
            Regex("""(?i)\b(?:file|source|src|videoUrl|video_url|video_alt_url)\s*[:=]\s*["'](https?[^"'<> ]{12,2500})["']"""),
            Regex("""(?i)\b(?:sources|formats)\s*[:=]\s*\[\s*\{\s*["']?(?:file|src|url)["']?\s*:\s*["'](https?[^"'<> ]{12,2500})["']""")
        )
        for(script in doc.select("script:not([src])").take(40)) {
            val body=script.data().ifBlank{script.html()}.take(100000)
            for(pattern in patterns)for(hit in pattern.findAll(body).take(12)){
                val link=https(hit.groupValues[1],doc.location())?:continue
                if(isMedia(link))found.putIfAbsent(link,Source(link,referer))
            }
        }
        return found.values.toList()
    }
    internal fun playerLink(doc:Document,owner:String):String? {
        val target=when(owner){
            "fullporner.com" -> "fullporner.com"
            "hqpornerxxx.com" -> "myxstudio.top"
            "uncensoredhentai.xxx" -> "nhplayer.com"
            else -> return null
        }
        val route=when(owner){
            "fullporner.com" -> Regex("""^/player/[0-9]{1,12}/[0-9]{1,4}/?$""")
            "hqpornerxxx.com" -> Regex("""^/embed/[A-Za-z0-9_-]{8,64}/?$""")
            else -> Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
        }
        for(iframe in doc.select("iframe[src]").take(30)){
            val candidate=https(iframe.attr("src"),doc.location())?:continue
            if(host(candidate)==target && route.matches(URI(candidate).path.orEmpty()))
                return candidate
        }
        return null
    }
    private fun selectedPlayer(context:Context,link:String,referer:String,
                               owner:String):Document {
        val h=host(link)
        val valid=when(owner){
            "fullporner.com" -> h=="fullporner.com" &&
                Regex("""^/player/[0-9]{1,12}/[0-9]{1,4}/?$""").matches(URI(link).path.orEmpty())
            "hqpornerxxx.com" -> h=="myxstudio.top" &&
                Regex("""^/embed/[A-Za-z0-9_-]{8,64}/?$""").matches(URI(link).path.orEmpty())
            "uncensoredhentai.xxx" -> h=="nhplayer.com" &&
                Regex("""^/v/[A-Za-z0-9]{8,40}/?$""").matches(URI(link).path.orEmpty())
            else->false
        }
        require(valid){"Untrusted publisher iframe"}
        val response=Jsoup.connect(link)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
            .header("Referer",referer)
            .header("Accept","text/html,application/xhtml+xml")
            .ignoreHttpErrors(true).followRedirects(false)
            .timeout(14_000).maxBodySize(1_800_000)
            .apply {
                NetworkRoute.javaProxy(context,referer)?.let{proxy(it)}
                if(host(link)==host(referer))
                    SiteSession.cookie(context,referer)?.takeIf{it.isNotBlank()}
                        ?.let{header("Cookie",it)}
            }.execute()
        check(response.statusCode() in 200..299){
            "Selected "+owner+" player HTTP "+response.statusCode()
        }
        return Jsoup.parse(response.body(),link)
    }
    private fun fromPlayer(context:Context,doc:Document,selected:String,
                           owner:String):List<Source>{
        val link=playerLink(doc,owner)?:return emptyList()
        var player=selectedPlayer(context,link,selected,owner)
        var playerPage=link
        // HQPorner publishes myxstudio.top/embed/<id>. That exact embedded
        // player publishes ONE mydaddy.cc/video/<id> player. Following only
        // this known chain is materially different from arbitrary iframe
        // interception or guessing a video URL.
        if(owner=="hqpornerxxx.com"){
            val child=player.select("iframe[src]").asSequence().mapNotNull{
                https(it.attr("src"),link)
            }.firstOrNull {
                host(it)=="mydaddy.cc" &&
                    Regex("""^/video/[A-Za-z0-9_-]{8,160}/?$""")
                        .matches(URI(it).path.orEmpty())
            }
            if(child!=null){
                val response=Jsoup.connect(child)
                    .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                    .header("Referer",link).ignoreHttpErrors(true)
                    .followRedirects(false).timeout(14_000)
                    .maxBodySize(1_800_000)
                    .apply {NetworkRoute.javaProxy(context,selected)
                        ?.let{proxy(it)}}.execute()
                if(response.statusCode() in 200..299){
                    player=Jsoup.parse(response.body(),child)
                    playerPage=child
                }
            }
        }
        val selectedMedia=(mediaElements(player,playerPage,
            "video[src],video source[src],source[type^=video][src],"+
                "meta[itemprop=contentUrl][content]")+
            jsSources(player,playerPage)).distinctBy{it.url}
        if(selectedMedia.isNotEmpty())return selectedMedia
        // This exact site's own iframe may create the HTML5 player only
        // after JavaScript initialization. Inspect that known iframe, not
        // every frame on the publisher page or unrelated browser traffic.
        val html=if(BrowserPageFetch.publicEligible(playerPage) ||
            SiteSession.bound(context,playerPage))
            runCatching{BrowserPageFetch.html(context,playerPage)}.getOrNull()
        else null
        if(!html.isNullOrBlank()){
            val rendered=Jsoup.parse(html,playerPage)
            val media=(mediaElements(rendered,playerPage,
                "main .video-player video[src],video[src][data-duration]")+
                jsSources(rendered,playerPage)).distinctBy{it.url}
            if(media.isNotEmpty())return media
        }
        // yt-dlp may have a dedicated extractor for the publisher-attested
        // player host, so give it the EXACT iframe rather than a made-up MP4.
        return listOf(Source(playerPage,if(playerPage==link)selected else link))
    }
    /** Roshy's selected video posts publish original file-host links,
     * NOT a stream. These URLs remain hoster landing pages to be resolved by
     * their own hoster transfer; a .mp4 path alone is NOT a media receipt.
     */
    internal fun roshy(doc:Document,selected:String):List<Source>{
        val out=LinkedHashMap<String,Source>()
        for(a in doc.select("article a[href],.entry-content a[href],main a[href]")
            .take(250)){
            val link=https(a.attr("href"),selected)?:continue
            val path=URI(link).path.orEmpty()
            val valid=when(host(link)){
                "k2s.cc"->path.startsWith("/file/") ||
                    path.startsWith("/download/")
                "nitroflare.com"->path.startsWith("/view/") ||
                    path.startsWith("/download/")
                "uploadgig.com"->path.startsWith("/file/") ||
                    path.startsWith("/download/")
                else->false
            }
            if(valid)out.putIfAbsent(link,Source(link,selected))
        }
        return out.values.toList()
    }
    /**
     * MP4Hentai is a VIDEO publisher. Its /YYYY/MM/<title> URL is only the
     * selected playback page. Extract the actual media from that selected
     * video's player; never download page HTML or unrelated article anchors.
     */
    internal fun mp4hentai(doc:Document,selected:String):List<Source>{
        if(!Regex("""^/20[0-9]{2}/(?:0[1-9]|1[0-2])/[^?#]{3,300}/?$""")
                .matches(URI(selected).path.orEmpty()))return emptyList()
        val player=mediaElements(doc,selected,
            "article video[src],article video source[src],"+
            ".entry-content video[src],.entry-content video source[src],"+
            "#player video[src],#player source[src],"+
            "meta[itemprop=contentUrl][content]")
        // A public first-party player may publish an exact media assignment.
        // No file in an unrelated recommendation/sidebar is an original.
        return (player+jsSources(doc,selected)).distinctBy{it.url}
    }
    internal fun selectedStatic(doc:Document,selected:String,owner:String):List<Source>{
        val css=when(owner){
            "helloporn.com"->"#player video[src],#player source[src],"+
                ".video-player video[src],.video-player source[src],"+
                "meta[itemprop=contentUrl][content]"
            "javideo.net"->"article video[src],article video source[src],"+
                ".video-player source[src],#video source[src]"
            "hentaibros.net","3dhentaix.com"->"main .video-player video[src],"+
                "main .video-player source[src]"
            "hentaihaven.xxx"->"article video[src],article video source[src],"+
                ".player video[src],.player source[src]"
            else->return emptyList()
        }
        val gated=owner=="hentaibros.net" || owner=="3dhentaix.com"
        val direct=mediaElements(doc,selected,css,gated)
        return (direct+(if(gated)emptyList() else jsSources(doc,selected)))
            .distinctBy{it.url}
    }
    fun resolve(context:Context,selected:String,doc:Document):
            List<PublisherOriginalExtractors.Candidate>{
        val owner=host(selected)
        if(owner !in owners || !VideoSiteProfiles.isVideoPage(selected))
            return emptyList()
        val originals=when(owner){
            "fullporner.com","hqpornerxxx.com","uncensoredhentai.xxx" ->
                fromPlayer(context,doc,selected,owner)
            "roshy.tv"->roshy(doc,selected)
            "mp4hentai.com"->{
                val first=mp4hentai(doc,selected)
                if(first.isNotEmpty())first
                else runCatching {
                    if(!BrowserPageFetch.publicEligible(selected) &&
                        !SiteSession.bound(context,selected))emptyList()
                    else mp4hentai(Jsoup.parse(
                        BrowserPageFetch.html(context,selected),selected),selected)
                }.getOrDefault(emptyList())
            }
            "helloporn.com","javideo.net","hentaibros.net",
            "3dhentaix.com","hentaihaven.xxx"->{
                selectedStatic(doc,selected,owner).takeIf{it.isNotEmpty()}?:
                    runCatching {
                        if(!BrowserPageFetch.publicEligible(selected) &&
                            !SiteSession.bound(context,selected))emptyList()
                        else selectedStatic(
                            Jsoup.parse(BrowserPageFetch.html(context,selected),
                                selected),selected,owner)
                    }.getOrDefault(emptyList())
            }
            else->emptyList()
        }
        return originals.distinctBy{it.url}.map{
            PublisherOriginalExtractors.Candidate(it.url,it.referer,owner)
        }
    }
}
