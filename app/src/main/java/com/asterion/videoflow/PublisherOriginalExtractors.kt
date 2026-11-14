package com.asterion.videoflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Site-owned selected-player extractors inside the ONE Asterion Downloader APK.
 * Each approved video publisher has an explicit extraction contract. A card,
 * preview, advertisement or successful HTTP page is never a media receipt.
 * Full downloads are still validated by NativeMediaDownloader / MediaStore.
 */
internal object PublisherOriginalExtractors {
    internal data class Candidate(val url:String,val referer:String,val host:String)
    internal data class Site(val host:String,val selectors:String,val requireDuration:Boolean=false,
                             val allowPlayerIframe:Boolean=false)
    private val sites=listOf(
        Site("pornhub.com","#player video[src],#player source[src],.video-player video[src],meta[itemprop=contentUrl]"),
        Site("eporner.com","#player video[src],#player source[src],meta[itemprop=contentUrl]"),
        Site("hqpornerxxx.com","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("jav.guru",".player iframe[src],#player iframe[src],main video[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("javideo.net","#player iframe[src],.video-player iframe[src],article video source[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("roshy.tv","#player video[src],.player source[src],article video[src],meta[itemprop=contentUrl]"),
        Site("cums.net","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("watchporn.to","meta[itemprop=contentUrl],#player source[src],#player video[src]"),
        Site("archivebate.com","#player iframe[src],.player iframe[src]",allowPlayerIframe=true),
        Site("xxxi.porn","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("helloporn.com","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("notfans.com","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("fullporner.com","#player iframe[src],.player iframe[src],iframe[src*='/player/'],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("xxxclub.to","#player video[src],#player source[src],.video-player video[src],meta[itemprop=contentUrl]"),
        Site("freepornvideos.xxx","#player video[src],#player source[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("omg.porn","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("hentaibros.net","main .video-player video[src][data-duration],main .video-player source[src][data-duration]",requireDuration=true),
        Site("mp4hentai.com","#player iframe[src],.video-player iframe[src],article video[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("onlyhentaistuff.com","#player iframe[src],.video-player iframe[src],article video[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("uncensoredhentai.xxx","iframe[src*='nhplayer.com/v/']",allowPlayerIframe=true),
        Site("3dhentaix.com","main .video-player video[src][data-duration],main .video-player source[src][data-duration]",requireDuration=true),
        Site("hentaihaven.xxx","#player iframe[src],.video-player iframe[src],article video[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("rule34video.com","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("camstreams.tv","#player video[src],.video-player source[src],meta[itemprop=contentUrl]")
    ).associateBy { it.host }

    val coveredHosts:Set<String> get()=sites.keys

    private fun domain(raw:String):String? {
        val u=runCatching{URI(raw)}.getOrNull()?:return null
        if(u.scheme!="https" || u.userInfo!=null || u.host.isNullOrBlank())return null
        val h=u.host.lowercase(Locale.ROOT)
        return sites.keys.firstOrNull { h==it || h=="www.$it" || h=="m.$it" ||
            h=="fr.$it" || h=="en.$it" }
    }
    fun owns(url:String)=domain(url)!=null

    private fun direct(raw:String):Boolean {
        val u=runCatching{URI(raw)}.getOrNull()?:return false
        return u.scheme=="https" && u.host!=null && u.userInfo==null &&
            u.host!="localhost" && NativeMediaDownloader.isDirectMediaUrl(raw)
    }

    private fun url(base:String,raw:String):String? {
        if(raw.isBlank() || raw.startsWith("data:") || raw.startsWith("blob:") ||
            raw.startsWith("javascript:"))return null
        return runCatching{URI(base).resolve(raw.replace("&amp;","&")).toString()}
            .getOrNull()?.takeIf{WebDiscovery.isUrl(it) && it.startsWith("https://")}
    }

    /** Provenance must point to the *selected* player. External iframe players
     * are restricted by site: never pass advertising frames to yt-dlp.
     */
    private fun iframeAllowed(host:String,link:String):Boolean {
        val u=runCatching{URI(link)}.getOrNull()?:return false
        val h=u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val path=u.path.orEmpty()
        return when(host) {
            "archivebate.com" -> h=="mixdrop.ag" &&
                Regex("""^/e/[A-Za-z0-9_-]{5,64}/?$""").matches(path)
            "uncensoredhentai.xxx" -> h=="nhplayer.com" &&
                Regex("""^/v/[A-Za-z0-9]{8,40}/?$""").matches(path)
            "fullporner.com" -> h=="fullporner.com" &&
                Regex("""^/player/[0-9]{1,12}/[0-9]{1,4}/?$""").matches(path)
            else -> VideoSiteProfiles.sameProvider("https://$host/",link) &&
                Regex("""(?i)^/(?:embed|player|video|watch)/[^/?#]+""").containsMatchIn(path)
        }
    }

    private fun structured(doc:Document):List<String> {
        val found=ArrayList<String>()
        // Only the VideoObject belonging to the selected item. A page's
        // unrelated recommendation JSON must not become the selected file.
        for(script in doc.select("script[type=application/ld+json]").take(12)){
            val content=script.data().ifBlank{script.html()}
            val matches=Regex("""(?is)"@type"\s*:\s*"VideoObject"[^}]{0,10000}""")
                .findAll(content)
            for(match in matches){
                val src=Regex("""(?i)"contentUrl"\s*:\s*"([^"]+)"""")
                    .find(match.value)?.groupValues?.get(1)?:continue
                found+=src.replace("\\/","/").replace("\\u0026","&")
            }
        }
        return found
    }

    /** Pure parser: real HTML fixtures are regression-tested per hostname.
     * No guessed media links, cross-site image previews, or DRM bypass.
     */
    fun fromHtml(selected:String,html:String):List<Candidate> {
        val host=domain(selected)?:return emptyList()
        val site=sites[host]?:return emptyList()
        if(!VideoSiteProfiles.isVideoPage(selected))return emptyList()
        val doc=Jsoup.parse(html,selected)
        val found=LinkedHashMap<String,Candidate>()
        fun add(raw:String,isFrame:Boolean=false,duration:Long?=null) {
            val link=url(selected,raw)?:return
            if(FullLengthVideoPolicy.isExplicitPreview("",link))return
            if(isFrame){
                if(!site.allowPlayerIframe || !iframeAllowed(host,link))return
            }else{
                if(!direct(link))return
                // Selected-player teaser-only publishers require an observed
                // full runtime, not arbitrary MP4 dimensions or byte count.
                if(site.requireDuration && (duration==null || duration<90L))return
            }
            found.putIfAbsent(link,Candidate(link,selected,host))
        }
        if(!site.requireDuration){
            for(raw in structured(doc))add(raw)
        }
        for(element in doc.select(site.selectors).take(25)){
            val tag=element.tagName()
            val isFrame=tag=="iframe"
            val duration=element.attr("data-duration").toLongOrNull()
                ?:element.closest("[data-duration]")?.attr("data-duration")?.toLongOrNull()
            val source=if(tag=="meta")element.attr("content") else
                element.attr("src").ifBlank{element.attr("data-src")}
            add(source,isFrame,duration)
        }
        return found.values.toList()
    }

    /** Uses existing same-site source/session handling, without a new APK,
     * embedded browser, login, or parallel queue implementation.
     */
    fun resolve(context:android.content.Context,selected:String):List<Candidate> {
        if(!owns(selected) || !VideoSiteProfiles.isVideoPage(selected))return emptyList()
        val host=domain(selected)
        // These two sites render a working HTML5 element only after JavaScript.
        // Parse the genuine in-app browser DOM and its real duration; their
        // static HTML contains clips that must not become saved originals.
        if(host=="hentaibros.net" || host=="3dhentaix.com"){
            val rendered=com.azrael.pixivdumpsync.BrowserPageFetch.html(
                context,selected)
            return fromHtml(selected,rendered)
        }
        val page=WebDiscovery.selectedDocument(selected,context)
        return fromHtml(selected,page.outerHtml())
    }
}
