package com.asterion.videoflow

import org.jsoup.Jsoup
import android.content.Context
import org.jsoup.nodes.Document
import com.azrael.galleryflow.SiteSession
import java.net.URI

data class VideoHit(val title:String, val url:String, val preview:String?=null)
data class VideoPage(val hits:List<VideoHit>,val next:String?)

/** Exact-page discovery only. No keyword search, indexing, Tor, or source expansion. */
object WebDiscovery {
    private val videoPaths = listOf(
        Regex("view_video\\.php\\?[^#]*viewkey=",RegexOption.IGNORE_CASE),
        Regex("/videos?/[^/?#]+",RegexOption.IGNORE_CASE),
        Regex("/watch(?:/[^/?#]+|\\?v=[^&#]+)",RegexOption.IGNORE_CASE),
        Regex("/shorts/[^/?#]+",RegexOption.IGNORE_CASE),
        Regex("/(?:post|clip|v)/[^/?#]+",RegexOption.IGNORE_CASE)
    )
    private val mediaFiles=Regex("\\.(mp4|webm|mov|m3u8|mpd)(?:[?#]|$)",RegexOption.IGNORE_CASE)
    private fun privateHost(host:String):Boolean {
        val h=host.lowercase()
        return h=="localhost"||h.endsWith(".local")||
            h=="127.0.0.1"||h=="0.0.0.0"||h=="::1"||h.startsWith("127.")||
            h.startsWith("192.168.")||h.startsWith("10.")||h.startsWith("169.254.")||
            (h.startsWith("172.") && (h.split('.').getOrNull(1)?.toIntOrNull()?.let{it in 16..31}==true))
    }
    fun isUrl(address:String):Boolean = try {
        val u=URI(address.trim())
        u.scheme in listOf("https","http") && !u.host.isNullOrBlank() && !privateHost(u.host) &&
            u.userInfo==null
    } catch(_:Exception){false}
    fun normalize(address:String):String?=try{
        if(!isUrl(address))null
        else {
            val u=URI(address.trim())
            // Preserve complete source URL, including signed media query params and host.
            EpornerOriginalSource.canonicalUrl(u.toASCIIString().substringBefore('#'))
                ?:u.toASCIIString().substringBefore('#')
        }
    } catch(_:Exception){null}
    fun isVideoPage(address:String):Boolean =
        mediaFiles.containsMatchIn(address) ||
        videoPaths.any { it.containsMatchIn(address) } ||
        VideoSiteProfiles.isVideoPage(address)
    /** Redirects are bounded to the same approved provider. Site cookies are
     * attached afresh to each same-provider request, never forwarded through
     * an automatic third-party redirect.
     */
    private fun open(url:String,context:Context?=null):Document{
        require(isUrl(url)){"Invalid public provider URL"}
        var current=url
        repeat(5){
            require(isUrl(current) && VideoSiteProfiles.sameProvider(url,current)){
                "Provider redirected outside its approved website"
            }
            val response=Jsoup.connect(current)
                .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
                .timeout(14_000).followRedirects(false).maxBodySize(2_500_000)
                .ignoreHttpErrors(true)
                .apply{
                    NetworkRoute.javaProxy(context,current)?.let{proxy(it)}
                    (context?.let{SiteSession.cookie(it,current)}
                        ?:SiteSession.cookie(current))?.let{header("Cookie",it)}
                }
                .execute()
            if(response.statusCode() in 300..399){
                val redirect=response.header("Location")
                    ?.takeIf{it.isNotBlank()}?:error("Provider redirect has no destination")
                val target=URI(current).resolve(redirect).toString()
                require(target.startsWith("https://") &&
                    isUrl(target) && VideoSiteProfiles.sameProvider(url,target)){
                    "Provider redirected to a different website; review source URL"
                }
                current=target
            }else{
                if(response.statusCode() in setOf(401,403) &&
                    context!=null &&
                    (SiteSession.bound(context,current) ||
                        com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(current))){
                    val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(context,current)
                    return org.jsoup.Jsoup.parse(html,current)
                }
                require(response.statusCode() in 200..299){
                    "Provider HTTP "+response.statusCode()+
                        if(response.statusCode() in setOf(401,403))
                            " · authorized login or browser verification needed"
                        else " · request unsuccessful"
                }
                val document=response.parse()
                // Archivebate's public homepage legitimately returns HTTP 200
                // with an 18+ Continue gateway and ZERO /watch/ links. Chrome
                // observed 36 genuine watch links only AFTER the user's
                // normal Continue action, and a real MP4 playback response.
                // For an already-connected site, read the owner's WebView
                // session instead of accepting a successful-but-empty shell.
                val sourceHost=runCatching{URI(url).host.orEmpty()
                    .lowercase().removePrefix("www.")}.getOrDefault("")
                if(sourceHost=="archivebate.com" && context!=null &&
                    SiteSession.bound(context,current) &&
                    document.select("a[href*='/watch/']").isEmpty() &&
                    document.text().contains(
                        "Explore the world's largest webcam archive",true)){
                    val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(
                        context,current)
                    val browser=Jsoup.parse(html,current)
                    require(browser.select("a[href*='/watch/']").isNotEmpty()){
                        "Archivebate connected browser still has no recordings; "+
                            "complete the site's public Continue step in CONNECT ACCOUNT"
                    }
                    return browser
                }
                val title=document.title().lowercase()
                require(!title.contains("just a moment") &&
                    !title.contains("access denied") &&
                    !title.contains("verify you are human")){
                    "Provider browser verification required; connect an authorized account"
                }
                return document
            }
        }
        error("Provider exceeded five same-site redirects")
    }
    fun findOnPage(address:String,limit:Int=15):List<VideoHit> {
        require(isUrl(address)){"Invalid public HTTP(S) page"}
        val root=open(address)
        val origin=address
        val found=linkedMapOf<String,VideoHit>()
        fun add(raw:String,title:String){
            val url=runCatching{URI(root.location()).resolve(raw).toString()}.getOrNull()?:return
            val normalized=normalize(url)?:return
            if(!VideoSiteProfiles.sameProvider(origin,normalized))return
            if(!isVideoPage(normalized))return
            found.putIfAbsent(normalized,VideoHit(title.trim().ifEmpty{"Video"}.take(180),normalized))
        }
        if(isVideoPage(root.location()))add(root.location(),root.title())
        root.select("a[href]").forEach {a->
            add(a.attr("abs:href"),a.attr("title").ifBlank{a.text()})
        }
        return found.values.take(limit.coerceIn(1,350))
    }

    /** One bounded source-page traversal. Caller saves 'next' before marking the page done. */
    fun page(address:String,root:String,context:Context?=null):VideoPage {
        require(isUrl(address)&&isUrl(root)){"Invalid source URL"}
        require(VideoSiteProfiles.sameProvider(root,address)){
            "Source pagination crossed providers"
        }
        val doc=try{
            open(address,context)
        }catch(error:Exception){
            // Retry as an authenticated WebView only after the user connected
            // this exact site. Do not bypass access controls on public sources.
            if(context==null || (
                !SiteSession.bound(context,address) &&
                !com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(address)))
                throw error
            val html=com.azrael.pixivdumpsync.BrowserPageFetch.html(context,address)
            Jsoup.parse(html,address)
        }
        val actual=URI(doc.location())
        require(VideoSiteProfiles.sameProvider(root,doc.location())){
            "Unexpected source redirect across provider domains"
        }
        val found=linkedMapOf<String,VideoHit>()
        for(a in doc.select("a[href]").take(1500)){
            val url=normalize(a.absUrl("href"))?:continue
            val uri=URI(url)
            if(!VideoSiteProfiles.sameProvider(root,url))continue
            if(!isVideoPage(url))continue
            val title=a.attr("title").ifBlank{a.text()}
                .ifBlank{a.selectFirst("img[alt]")?.attr("alt").orEmpty()}
                .ifBlank{"Video"}.take(180)
            val thumbnail=a.selectFirst("img")?.let{
                com.azrael.pixivdumpsync.SitePreviewImage.best(it)
            }
            found.putIfAbsent(url,VideoHit(title,url,thumbnail))
            if(found.size>=350)break
        }
        // Some sites put the accessible video original only in a native HTML
        // <video> element. Preserve the distinct page/source and thumbnail.
        // On Archivebate the <video> elements shown around /watch/ID
        // are previews of OTHER recordings. Chrome confirmed that the
        // selected player is a separate Mixdrop iframe. Never publish these
        // preview clips as full downloadable originals.
        val archivebateCatalog=runCatching{
            URI(root).host.orEmpty().lowercase().removePrefix("www.")==
                "archivebate.com"
        }.getOrDefault(false)
        for(node in (if(archivebateCatalog)emptyList()
            else doc.select("main video[src],main video source[src],"+
                "article video[src],article video source[src],"+
                ".post video[src],.post video source[src]"))){
            val candidate=normalize(node.absUrl("src"))?:continue
            if(!mediaFiles.containsMatchIn(candidate))continue
            val video=node.closest("video")?:node
            val title=node.closest("article")?.selectFirst("h1,h2,h3")?.text()
                ?.takeIf{it.length>=3} ?: doc.title().takeIf{it.length>=3}
                ?: "Video"
            val poster=video.absUrl("poster").takeIf{isUrl(it)}
            found.putIfAbsent(candidate,VideoHit(title.take(180),candidate,poster))
            if(found.size>=350)break
        }
        val rootPath=URI(root).path.trimEnd('/')
        val explicit=doc.selectFirst("a[rel=next][href],.pagination a.next[href],"+
            "a.next[href],link[rel=next][href]")?.absUrl("href")
        val next=(explicit?:VideoSiteProfiles.next(doc,root,address)
            ?:com.azrael.pixivdumpsync.SourceIndexParser.next(doc,root,address))
            ?.let(::normalize)?.takeIf{candidate->
                val u=URI(candidate)
                VideoSiteProfiles.sameProvider(root,candidate) &&
                    (rootPath.isEmpty() || u.path.startsWith(rootPath) ||
                        u.query.orEmpty().contains("page=") ||
                        u.path.contains("/page/")) &&
                    candidate!=address && candidate!=root
            }
        return VideoPage(found.values.toList(),next)
    }

    /** Extracts only original public-site MP4 links from the source page, not trailers. */
    internal fun watchPornDirect(html:String):String?{
        val regex=Regex("""(?:video_alt_url|video_url)\s*:\s*'([^']+)'""")
        return regex.findAll(html).map{it.groupValues[1].replace("&amp;","&")}
            .filter{
                val u=runCatching{URI(it)}.getOrNull()
                u?.scheme=="https" && u.host=="watchporn.to" &&
                    u.path.orEmpty().contains("/get_file/") &&
                    u.path.orEmpty().contains(".mp4") &&
                    !u.path.orEmpty().contains("preview",true)
            }.lastOrNull()
    }


    /** One independently resumable WatchPorn performer page.
     * Each next cursor is an exact subpath of the approved performer profile.
     * Video pages and previews are never confused with pagination links.
     */
    fun watchPornArtistPage(profile:String,address:String,context:Context?=null):VideoPage {
        val first=URI(profile)
        require(first.scheme=="https" && first.host=="watchporn.to" &&
            Regex("^/models/[a-zA-Z0-9_-]+/?$").matches(first.path)){
            "Expected a WatchPorn performer URL"
        }
        val base="https://watchporn.to"+first.path.trimEnd('/')+"/"
        val target=URI(address)
        require(target.scheme=="https" && target.host=="watchporn.to" &&
            (target.path==first.path.trimEnd('/') ||
             target.path==first.path.trimEnd('/')+"/" ||
             Regex("^"+Regex.escape(first.path.trimEnd('/'))+"/[0-9]+/?$")
                 .matches(target.path))) {
            "Performer page crossed to a different source"
        }
        val doc=open(address,context)
        val location=URI(doc.location())
        require(location.scheme=="https" && location.host=="watchporn.to" &&
            location.path.startsWith(base.removePrefix("https://watchporn.to"))) {
            "Performer page redirected"
        }
        val found=LinkedHashMap<String,VideoHit>()
        for(a in doc.select("a[href*=/video/]").take(700)){
            val url=runCatching{location.resolve(a.attr("href")).toString()}
                .getOrNull()?:continue
            val u=runCatching{URI(url)}.getOrNull()?:continue
            if(u.scheme!="https" || u.host!="watchporn.to" ||
                !Regex("^/video/[0-9]+/[^?#]*$").matches(u.path))continue
            val title=a.attr("title").ifBlank{
                a.selectFirst("img[alt]")?.attr("alt").orEmpty()
            }.ifBlank{a.text()}.ifBlank{"WatchPorn video"}
            val thumb=a.selectFirst("img")?.let{img->
                listOf("data-original","data-src","src")
                    .map{img.absUrl(it)}.firstOrNull{isUrl(it)}
            }
            found.putIfAbsent(url,VideoHit(title.take(180),url,thumb))
        }
        val next=doc.selectFirst(".wp-pag__next a[href],"+
            ".pagination a.next[href],a[rel=next][href]")
            ?.absUrl("href")
        val nextUri=next?.let{runCatching{URI(it)}.getOrNull()}
        val cursor=nextUri?.takeIf{
            it.scheme=="https" && it.host=="watchporn.to" &&
            Regex("^"+Regex.escape(first.path.trimEnd('/'))+"/[0-9]+/?$")
                .matches(it.path) && it.toString()!=address
        }?.toString()
        return VideoPage(found.values.toList(),cursor)
    }

    /** Compatibility API for existing callers; backfill uses watchPornArtistPage
     * with persisted cursor instead of silently stopping after 80 pages.
     */
    fun findWatchPornArtist(address:String,full:Boolean):List<VideoHit>{
        val found=LinkedHashMap<String,VideoHit>()
        val visited=HashSet<String>()
        var cursor:String?=address
        repeat(if(full)8 else 1){
            val current=cursor?:return@repeat
            if(!visited.add(current))return@repeat
            val page=watchPornArtistPage(address,current)
            for(item in page.hits)found.putIfAbsent(item.url,item)
            cursor=page.next
        }
        return found.values.toList()
    }

    /** Official embedded-player link advertised by the selected, approved
     * publisher page. This is NOT an extra catalog provider or a downloader
     * for arbitrary third-party advertising iframes. No authentication is
     * bypassed; provider access errors remain errors.
     */
    internal fun publishedEmbeds(doc:Document,source:String,
                                 explicitlySelected:Boolean=false):List<String> {
        val origin=runCatching{URI(source)}.getOrNull()?:return emptyList()
        val host=origin.host.orEmpty().lowercase().removePrefix("www.")
        // A verified user tap on a site's media card may use a slug that its
        // old provider route no longer recognizes. The caller already checks
        // it is an approved provider; keep the same player-provenance checks.
        if(!VideoSiteProfiles.isVideoPage(source) && !explicitlySelected)
            return emptyList()
        val authorizedArchivebate=host=="archivebate.com" &&
            Regex("^/watch/[0-9]{1,16}/?$").matches(origin.path.orEmpty())
        val uncensored=host=="uncensoredhentai.xxx"
        val result=LinkedHashSet<String>()
        // Older code ignored published players on every other video site.
        // Read only video-player iframes from the selected publisher's page:
        // never blanket-follow ad/analytics iframes or promote a catalog card
        // as a verified original. Extractor and final file checks still apply.
        val selectors="iframe[src][allowfullscreen],iframe[src][allow*=fullscreen],"+
            "#player iframe[src],.video-player iframe[src],"+
            ".player iframe[src],iframe[src*='mixdrop.ag/e/']"
        for(iframe in doc.select(selectors).take(30)){
            val url=runCatching{
                URI(doc.location()).resolve(iframe.attr("src"))
            }.getOrNull()?:continue
            if(url.scheme!="https" || url.userInfo!=null || url.host==null)
                continue
            val embedHost=url.host.lowercase().removePrefix("www.")
            val path=url.path.orEmpty().lowercase()
            val accepted=when {
                authorizedArchivebate -> embedHost=="mixdrop.ag" &&
                    Regex("""^/e/[A-Za-z0-9_-]{5,64}/?$""")
                        .matches(url.path.orEmpty())
                uncensored -> embedHost=="nhplayer.com" &&
                    Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                        .matches(url.path.orEmpty())
                else -> {
                    val advertisedPlayer=iframe.hasAttr("allowfullscreen") ||
                        iframe.attr("allow").contains("fullscreen",true) ||
                        iframe.parents().take(3).any{
                            (it.id()+" "+it.className()).contains("player",true)
                        }
                    val adPath=Regex("""(?i)(?:^|/)(?:ads?|banner|popunder|tracking|analytics|click|redirect)(?:/|$)""")
                    // These two players have source-specific provenance
                    // checks above. An unrelated site's fullscreen advert
                    // must not be mistaken for the selected publisher video.
                    // Reject lookalike specialist domains too: an iframe
                    // pointing at nhplayer.com.evil.example is not NHPlayer.
                    // A cross-provider iframe titled as advertising is also
                    // never evidence of the selected video.
                    val specialistElsewhere=listOf(
                        "nhplayer.com","mixdrop.ag").any{
                        embedHost==it || embedHost.contains("."+it+".") ||
                            embedHost.startsWith(it+".")
                    }
                    val advertisedAsAd=listOf("advertisement","banner","sponsored")
                        .any{iframe.attr("title").contains(it,true)}
                    advertisedPlayer && !specialistElsewhere &&
                        !advertisedAsAd && !adPath.containsMatchIn(path) &&
                        url.toString()!=source
                }
            }
            if(!accepted)continue
            result+=url.toString().substringBefore('#')
            if(result.size>=5)break
        }
        return result.toList()
    }
    fun embeddedCandidates(address:String,context:Context?=null,
                           explicitlySelected:Boolean=false):List<String> {
        if(!isUrl(address) ||
            (!VideoSiteProfiles.isVideoPage(address) && !explicitlySelected))
            return emptyList()
        // The user clicked this provider's actual media card even if the
        // publisher has changed its slug pattern. Inspect its selected page
        // rather than dropping every player because the old route mismatched.
        if(explicitlySelected &&
            !VideoSiteProfiles.coveredHosts.any{host->
                val actual=runCatching{
                    java.net.URI(address).host.orEmpty().lowercase()
                        .removePrefix("www.").removePrefix("m.")
                }.getOrDefault("")
                actual==host
            })return emptyList()
        val doc=open(address,context)
        return publishedEmbeds(doc,address,explicitlySelected)
    }

    /** Real 2026-11-13 Chrome tests proved these public pages display 5–30s
     * teasers but no publicly verified full recording. Only accept a REAL
     * rendered video.currentSrc with a publisher-visible duration >=90s;
     * never promote poster URLs, recommendations or ad/trailer clips.
     */
    internal data class SelectedBrowserMedia(
        val original:String?,val previewOnly:Boolean
    )
    internal fun selectedBrowserMedia(html:String,pageUrl:String):SelectedBrowserMedia {
        val document=org.jsoup.Jsoup.parse(html,pageUrl)
        var preview=false
        for(video in document.select("main .video-player video[src][data-duration]")){
            val duration=video.attr("data-duration").toLongOrNull()?:continue
            val value=video.attr("abs:src").ifBlank{
                runCatching{URI(pageUrl).resolve(video.attr("src")).toString()}
                    .getOrDefault("")
            }
            if(!isUrl(value) || !mediaFiles.containsMatchIn(value))continue
            if(duration in 1..89)preview=true
            if(duration in 90..(24L*3600))
                return SelectedBrowserMedia(value,preview)
        }
        return SelectedBrowserMedia(null,preview)
    }

    fun directMedia(address:String,context:Context?=null):String? {
        require(isUrl(address)){"Invalid public HTTP(S) URL"}
        if(mediaFiles.containsMatchIn(address))return address
        // A selected Archivebate /watch/<ID> page has recommendation MP4s
        // beside its real embedded watch player. The former is not the
        // selected original and must NEVER be saved under its title.
        if(runCatching{
            val uri=URI(address)
            uri.host.orEmpty().lowercase().removePrefix("www.")=="archivebate.com" &&
                Regex("""^/watch/[0-9]{1,16}/?$""").matches(uri.path.orEmpty())
        }.getOrDefault(false))return null
        val page=open(address,context)
        if(URI(address).host.orEmpty().endsWith("watchporn.to")){
            watchPornDirect(page.html())?.let{return it}
        }
        // For these selected JS-driven sites, an HTML video element may be
        // ONLY a 5–30s preview. It must pass the rendered duration gate below.
        val selectedBrowserHost=runCatching{URI(address).host.orEmpty()
            .lowercase().removePrefix("www.")}.getOrDefault("")
        if(selectedBrowserHost !in setOf("hentaibros.net","3dhentaix.com"))
        for(node in page.select("video[src],video source[src],source[src],meta[property=og:video],meta[property=og:video:url],meta[itemprop=contentUrl]")){
            val source=node.attr("abs:src").ifBlank{node.attr("content")}
            val resolved=runCatching{URI(page.location()).resolve(source).toString()}.getOrNull()?:continue
            if(isUrl(resolved)&&mediaFiles.containsMatchIn(resolved))return resolved
        }
        // Public Chrome renders a genuine HTML5 player only AFTER JavaScript
        // execution for these two publishers. Never mistake their teaser clips
        // (observed 5–30 seconds) for the requested full-length original.
        val selectedHost=runCatching{URI(address).host.orEmpty()
            .lowercase().removePrefix("www.")}.getOrDefault("")
        if(context!=null &&
            selectedHost in setOf("hentaibros.net","3dhentaix.com")){
            val rendered=com.azrael.pixivdumpsync.BrowserPageFetch
                .html(context,address)
            val inspected=selectedBrowserMedia(rendered,address)
            if(inspected.original!=null)return inspected.original
            if(inspected.previewOnly)throw IllegalStateException(
                "Publisher only exposed short preview clips; full video "+
                    "authentication required. Connect your authorized site "+
                    "session before retrying, or use the publisher's genuine "+
                    "original media URL.")
        }
        return null
    }
}
