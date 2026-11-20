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
        Site("archivebate.com","#player iframe[src],.player iframe[src],iframe[src^='https://mixdrop.ag/e/']",allowPlayerIframe=true),
        // LiveCamRips' exact /video/<id> publisher page embeds the recording
        // in one iframe#iframe-video. Other iframes/ads are not originals.
        Site("livecamrips.to","iframe#iframe-video[src]",allowPlayerIframe=true),
        Site("onscreens.me","#player video[src],main video[src]",allowPlayerIframe=true),
        Site("xxxi.porn","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("notfans.com","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("fullporner.com","#player iframe[src],.player iframe[src],iframe[src*='/player/'],meta[itemprop=contentUrl]",allowPlayerIframe=true),

        Site("freepornvideos.xxx","#player video[src],#player source[src],.video-player source[src],meta[itemprop=contentUrl]"),
        // Only the selected article's own main player is eligible. No sidebar,
        // thumbnail MP4s, related posts, or guessed off-site player domains.
        Site("pornbuz.com","a[href^='https://streamtape.com/v/'],a[href^='https://streamtape.to/v/']"),
        Site("porneec.com","main .video-player video[src],main .video-player source[src],"+
            "article .video-player video[src],article .video-player source[src],"+
            "video[src][data-duration],video source[src][data-duration]",
            requireDuration=true),
        Site("taiav.com","main .video-player video[src],main .video-player source[src],"+
            "article .video-player video[src],article .video-player source[src],"+
            "video[src][data-duration],video source[src][data-duration]",
            requireDuration=true),
        Site("omg.porn","#player video[src],.video-player source[src],meta[itemprop=contentUrl]"),
        Site("hentaibros.net","main .video-player video[src][data-duration],main .video-player source[src][data-duration]",requireDuration=true),
        Site("onlyhentaistuff.com","#player iframe[src],.video-player iframe[src],article video[src],meta[itemprop=contentUrl]",allowPlayerIframe=true),
        Site("uncensoredhentai.xxx","iframe[src*='nhplayer.com/v/']",allowPlayerIframe=true),
        Site("3dhentaix.com","main .video-player video[src][data-duration],main .video-player source[src][data-duration]",requireDuration=true),
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
        val selectedManifest=Regex("""(?i)\.(?:m3u8|mpd)$""")
            .containsMatchIn(u.path.orEmpty())
        return u.scheme=="https" && u.host!=null && u.userInfo==null &&
            u.host!="localhost" &&
            (NativeMediaDownloader.isDirectMediaUrl(raw) || selectedManifest)
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
            "livecamrips.to" -> h in setOf(
                "mdzsmutpcvykb.net","mixdrop.ag","mixdrop.co","mixdrop.to") &&
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
        if(host=="pornbuz.com"){
            // PornBuz's second server is a publisher-labelled selected
            // Streamtape watch URL. Never infer the original from generic
            // MP4s embedded in sidebar/recommendation blocks.
            return PornBuzOriginalSource.published(selected,html).map{
                Candidate(it,selected,host)
            }
        }
        // Rule34Video publishes quality-labelled download=true links in the
        // selected page, not <video> sources. Its own first-party resolver
        // checks the selected ID and anchor label before admission.
        if(host=="rule34video.com") {
            for(original in Rule34VideoOriginalSource.parse(html,selected)){
                found.putIfAbsent(original.url,
                    Candidate(original.url,selected,host))
            }
        }
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
            // A related panel can contain a perfectly playable full-length
            // MP4. Proven duration alone never makes it the selected item.
            if(element.closest("aside,.related,.recommended,.recommendations,"+
                    ".ads,.advertisement,.sidebar,.more-videos,[data-ad]")!=null)
                continue
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
        val page=WebDiscovery.selectedDocument(selected,context)
        // Real Pornhub selected watch pages publish their signed original
        // HLS quality variants in one first-party flashvars_<id> JSON object.
        // Initial HTML5 video/src is absent; generic CSS only finds previews.
        if(host=="pornhub.com"){
            val exact=PornhubOriginalSource.published(selected,page.outerHtml())
            if(exact.isNotEmpty())return exact.map{
                Candidate(it.url,selected,host)
            }
            // A browser session can legitimately render a selected player
            // unavailable in bare HTTP. Keep the SAME watch/viewkey and do
            // not promote any recommendation, preview or advertising source.
            val rendered=runCatching {
                if(com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(selected))
                    com.azrael.pixivdumpsync.BrowserPageFetch
                        .html(context,selected)
                else null
            }.getOrNull()
            val fallback=rendered?.let {
                PornhubOriginalSource.published(selected,it)
            }.orEmpty()
            return fallback.map {Candidate(it.url,selected,host)}
        }
        // The selected FreePornVideos video publishes actual full-quality
        // MP4 <source> links under its own /get_file/ endpoint. Dispatch the
        // selected-ID-bound publisher source directly; Python page metadata
        // extraction is not a prerequisite for a real first-party MP4.
        if(host=="freepornvideos.xxx"){
            val exact=FreePornVideosOriginalSource.selectedOriginal(page,selected)
            if(exact!=null)return listOf(Candidate(exact,selected,host))
        }
        // Archivebate's watch page frequently returns its cover cards
        // BEFORE the exact Mixdrop player is inserted by JavaScript. If
        // static selected HTML is empty, inspect ONLY publisher-selected
        // rendered iframe candidates. Generic watch-page Python extraction
        // then cannot mask a missing selected player with "Unsupported URL".
        if(host=="onscreens.me"){
            // Open the video player on the exact selected recording. Nitroflare
            // and the site's recommendations are NOT equivalent to a free
            // selected playable source and must not be blindly scraped.
            val selectedPlayer=OnScreensOriginalSource.published(
                selected,page.outerHtml())
            return selectedPlayer?.let{listOf(Candidate(it,selected,host))}
                .orEmpty()
        }
        if(host=="archivebate.com" &&
            ArchivebateSelectedMedia.isSelectedArticle(selected)){
            val static=fromHtml(selected,page.outerHtml())
            if(static.isNotEmpty())return static
            val rendered=WebDiscovery.embeddedCandidates(
                selected,context,explicitlySelected=true)
                .filter{ArchivebateSelectedMedia.isSelectedEmbed(selected,it)}
                .distinct()
            return rendered.singleOrNull()?.let{
                listOf(Candidate(it,selected,host))
            }.orEmpty()
        }
        // The real Rule34Video download-quality links can be created only
        // after the selected page's Download control opens. Static HTML alone
        // must not be interpreted as "no original" before inspecting the
        // authorized rendered selected page.
        if(host=="rule34video.com"){
            fun published(html:String)=Rule34VideoOriginalSource.parse(html,selected)
                .map{Candidate(it.url,selected,host)}
            val static=published(page.outerHtml())
            if(static.isNotEmpty())return static
            // Keep the actual browser exception (e.g. HTTP 503 / access
            // challenge); do not silently replace it with a generic player.
            check(com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(selected) ||
                com.azrael.galleryflow.SiteSession.bound(context,selected)){
                "Rule34Video selected original requires accessible browser session"
            }
            val rendered=com.azrael.pixivdumpsync.BrowserPageFetch
                .html(context,selected)
            return published(rendered)
        }
        // Do not short-circuit HentaiBros or 3DHentai ahead of their
        // publisher-specific resolver: the old early return silently skipped
        // the real player and only inspected static teaser tags.
        // The broken providers do not share one CSS media contract. Their
        // own selected-player resolver handles site-owned iframe, WordPress
        // post, authorized rendered player or file hoster metadata.
        if(SiteSpecificOriginalSources.owns(selected)){
            val resolved=SiteSpecificOriginalSources.resolve(context,selected,page)
            if(host=="jav.guru" && resolved.isEmpty() &&
                com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(selected)){
                // On Android a publisher can serve HTTP 200 with a thin
                // JavaScript shell. The static HTML lacks shortcode keys;
                // retry the SAME selected article through WebView's rendered
                // shortcode snapshot rather than treating the shell as video.
                val rendered=com.azrael.pixivdumpsync.BrowserPageFetch
                    .html(context,selected)
                val browser=Jsoup.parse(rendered,selected)
                val browserOriginals=SiteSpecificOriginalSources.resolve(
                    context,selected,browser)
                if(browserOriginals.isNotEmpty())
                    return browserOriginals.distinctBy{it.url}
            }
            // Retain publisher-attested fallbacks only; never substitute
            // generic page thumbnails, ads, or another selected item.
            // 3DHentaiX can expose unrelated playable recommendations.
            // Only its unique, selected main player may supply media; do not
            // reintroduce broad CSS or metadata candidates on this provider.
            // All providers with exact selected-player adapters must not
            // append unrelated article/metadata media when the exact player
            // transfer fails. Such fallbacks caused wrong-scene downloads.
            if(host in setOf("3dhentaix.com","javideo.net","helloporn.com",
                    "jav.guru","hentaibros.net","fullporner.com",
                    "hqpornerxxx.com","uncensoredhentai.xxx","mp4hentai.com",
                    "roshy.tv","hentaihaven.xxx"))
                return resolved.distinctBy{it.url}
            return (resolved+fromHtml(selected,page.outerHtml()))
                .distinctBy{it.url}
        }
        if(host=="eporner.com") {
            // The first-party Eporner xhr/video JSON is authoritative for
            // selected quality variants; og:video and page thumbnails are not.
            val formats=EpornerOriginalSource.publishedSources(
                selected,page,context).map {Candidate(it,selected,host)}
            return (formats+fromHtml(selected,page.outerHtml()))
                .distinctBy{it.url}
        }
        if(host=="taiav.com"){
            // 2026-11-20 PC verification: actual first-party selected player
            // GET /api/getmovie?type=<published quality>&id=<selected movie>
            // supplies JSON.m3u8. The separate /previewvideo endpoint is only
            // a short thumbnail clip and MUST NEVER become a saved original.
            // Do not fall through to unrelated rendered article teaser MP4s.
            return TaiAvOriginalSource.resolve(context,selected,page.outerHtml())
                ?.let{listOf(Candidate(it,selected,host))}.orEmpty()
        }
        if(host=="porneec.com"){
            // Preserve working Porneec selected HTML5 player unchanged.
            // Static ad/preview media cannot stand in for the original.
            val static=fromHtml(selected,page.outerHtml())
            if(static.isNotEmpty())return static
            val snapshot=com.azrael.pixivdumpsync.BrowserPageFetch
                .html(context,selected)
            return fromHtml(selected,snapshot).distinctBy{it.url}
        }
        return fromHtml(selected,page.outerHtml())
    }
}
