package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
import com.azrael.pixivdumpsync.BrowserPageFetch
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/** Exact selected-publisher resolvers. Never treat cards, ads or thumbnails as originals. */
internal object SiteSpecificOriginalSources {
    data class Source(val url:String,val referer:String)
    private val owners=setOf("fullporner.com","hqpornerxxx.com","helloporn.com",
        "jav.guru","javideo.net","roshy.tv","mp4hentai.com","hentaibros.net",
        "3dhentaix.com","hentaihaven.xxx","uncensoredhentai.xxx")
    internal fun host(url:String):String=runCatching {
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")
    fun owns(url:String)=host(url) in owners
    /** File-hosting landing pages are not MP4 media even if their title
     * ends in .mp4. Their provider must resolve them separately.
     */
    internal fun isHosterPage(link:String):Boolean {
        val path=runCatching{URI(link).path.orEmpty()}.getOrDefault("")
        return when(host(link)){
            "k2s.cc"->path.startsWith("/file/") ||
                path.startsWith("/download/")
            "nitroflare.com"->path.startsWith("/view/") ||
                path.startsWith("/download/")
            "uploadgig.com"->path.startsWith("/file/") ||
                path.startsWith("/download/")
            else->false
        }
    }
    private fun https(raw:String,base:String):String? {
        if(raw.isBlank() || listOf("blob:","javascript:","data:").any{
            raw.startsWith(it,true)})return null
        val u=runCatching{URI(base).resolve(raw.trim().replace("&amp;","&")
            .replace("\\/","/").replace("\\u0026","&").replace(" ","%20"))}
            .getOrNull()?:return null
        if(u.scheme!="https" || u.host.isNullOrBlank() ||
            u.userInfo!=null || u.port!=-1 ||
            !WebDiscovery.isUrl(u.toString()))return null
        return u.toASCIIString().substringBefore('#')
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
            // A publisher player may contain a sidebar/advertisement video.
            // Those are never the selected original, even inside the same
            // .video-player wrapper.
            if(element.closest("aside,.related,.recommendations,.ads,"+
                    ".advertisement,.banner,.sidebar,[data-ad]")!=null)continue
            val raw=element.attr("src").ifBlank{element.attr("data-src")}
                .ifBlank{element.attr("content")}
            val link=https(raw,doc.location())?:continue
            // Browser currentSrc can be available before duration metadata.
            // A missing duration is not evidence that this is a short teaser.
            // Known sub-90-second previews remain excluded.
            val seconds=runtime(element)
            if(!isMedia(link) ||
                (minimumRuntime && seconds!=null && seconds in 1L..89L))
                continue
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
        // The selected iframe is an independent site. Using the watch
        // publisher's cookie (or none) on nhplayer.com turns an authorized
        // browser session into HTTP 403 before the player can be examined.
        // A failed static request must reach the existing, owner-authorized
        // WebView renderer instead of aborting the whole selected original.
        val response=runCatching {
            Jsoup.connect(link)
                .userAgent(NativeMediaDownloader.USER_AGENT)
                .header("Referer",referer)
                .header("Accept","text/html,application/xhtml+xml")
                .ignoreHttpErrors(true).followRedirects(false)
                .timeout(14_000).maxBodySize(1_800_000)
                .apply {
                    NetworkRoute.javaProxy(context,referer)?.let{proxy(it)}
                    SiteSession.cookie(context,link)?.takeIf{it.isNotBlank()}
                        ?.let{header("Cookie",it)}
                }.execute()
        }.getOrNull()
        if(response!=null && response.statusCode() in 200..299 &&
            response.contentType().contains("html",ignoreCase=true)) {
            val page=Jsoup.parse(response.body(),link)
            if(!SiteSpecificPlayerPolicy.accessGate(page))
                return page
        }
        // Only render the already-published, verified iframe, never arbitrary
        // ads, related posts or cross-domain redirect destinations.
        if(BrowserPageFetch.publicEligible(link) || SiteSession.bound(context,link)){
            val rendered=runCatching {
                BrowserPageFetch.html(context,link,playerReferer=referer)
            }.getOrNull()
            if(!rendered.isNullOrBlank()){
                val page=Jsoup.parse(rendered,link)
                if(!SiteSpecificPlayerPolicy.accessGate(page))
                    return page
            }
        }
        error("Selected "+owner+" player requires its authorized site session "+
            "or browser verification; no selected original was exposed")
    }
    /** FullPorner serves original MP4 files at /vid/<id> without an .mp4
     * suffix. Unlike general mediaElements(), this admits ONLY files linked
     * from the verified selected /player/ HTML5 source element.
     */
    internal fun fullPornerSelectedPlayer(
        doc:Document,selected:String,playerPage:String
    ):List<Source>{
        if(!FullPornerOriginalSource.selectedPage(selected) ||
            !FullPornerOriginalSource.selectedPlayer(playerPage))return emptyList()
        val out=LinkedHashMap<String,Source>()
        for(node in doc.select("video#flvv source[src],video#flvv[src],"+
            "#player video source[src],#player source[type^=video][src]").take(12)){
            if(node.closest("aside,.related,.recommendations,.ads,.sidebar")!=null)
                continue
            val raw=node.attr("src")
            val link=https(raw,playerPage)?:continue
            if(FullPornerOriginalSource.verifiedMedia(selected,playerPage,link))
                out.putIfAbsent(link,Source(link,playerPage))
        }
        val scriptQualities=FullPornerOriginalSource.publishedQualities(
            selected,playerPage,doc.outerHtml()).map{Source(it,playerPage)}
        return (scriptQualities+out.values).distinctBy{it.url}
    }

    /** The owner's selected NHPlayer /v/id publishes its ONLY first-party
     * server in .servers li[data-id]. The /v/ page is merely a selector,
     * not a media file; its first server is /player.php?<publisher-token>.
     * Never infer a second server, reuse unrelated iframe media, or invent a
     * signed URL. HTML verification/CAPTCHA remains user-authorized only.
     */
    internal fun selectedNhPlayerServer(iframe:String,html:String):String? {
        val page=runCatching{URI(iframe)}.getOrNull()?:return null
        if(page.scheme!="https" || page.host!="nhplayer.com" ||
            page.userInfo!=null || page.port!=-1 ||
            !page.rawQuery.isNullOrBlank() || page.rawFragment!=null ||
            !Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                .matches(page.path.orEmpty()))return null
        val document=Jsoup.parse(html,iframe)
        val published=LinkedHashSet<String>()
        for(item in document.select(".servers li[data-id],"+
                "ul.servers li[data-id],#servers li[data-id]").take(12)){
            val raw=item.attr("data-id").trim()
            if(raw.isBlank() || raw.length>2200)continue
            val dest=runCatching{page.resolve(raw)}.getOrNull()?:continue
            if(dest.scheme!="https" || dest.host!="nhplayer.com" ||
                dest.userInfo!=null || dest.port!=-1 ||
                dest.path!="/player.php" || dest.rawFragment!=null ||
                dest.rawQuery.isNullOrBlank() ||
                dest.rawQuery.length>1500)continue
            published.add(dest.toString())
        }
        return published.singleOrNull()
    }

    private fun fromPlayer(context:Context,doc:Document,selected:String,
                           owner:String):List<Source>{
        // A publisher may insert its selected iframe only after the watch
        // page initializes JavaScript. Inspect that exact page once before
        // returning no candidates to the generic, unsupported yt-dlp path.
        val initial=playerLink(doc,owner)
        val rendered=if(initial==null &&
            (BrowserPageFetch.publicEligible(selected) ||
             SiteSession.bound(context,selected)))
            runCatching {
                Jsoup.parse(BrowserPageFetch.html(context,selected),selected)
            }.getOrNull() else null
        val link=initial ?: rendered?.let{playerLink(it,owner)}
            ?:return emptyList()
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
                    .userAgent(NativeMediaDownloader.USER_AGENT)
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
        if(owner=="uncensoredhentai.xxx"){
            val exactServer=selectedNhPlayerServer(playerPage,player.outerHtml())
            if(exactServer!=null){
                // The publisher-declared selected NHPlayer server is
                // independent of the watch-page cookies. Normal user/site
                // verification must complete; never forge challenge tokens.
                val response=runCatching {
                    Jsoup.connect(exactServer)
                        .userAgent(NativeMediaDownloader.USER_AGENT)
                        .header("Referer",playerPage)
                        .ignoreHttpErrors(true).followRedirects(false)
                        .timeout(14_000).maxBodySize(1_600_000)
                        .apply {
                            NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                            SiteSession.cookie(context,exactServer)
                                ?.takeIf{it.isNotBlank()}
                                ?.let{header("Cookie",it)}
                        }.execute()
                }.getOrNull()
                if(response!=null && response.statusCode() in 200..299 &&
                    response.contentType().orEmpty()
                        .contains("html",ignoreCase=true)){
                    val parsed=Jsoup.parse(response.body(),exactServer)
                    if(!SiteSpecificPlayerPolicy.accessGate(parsed)){
                        player=parsed
                        playerPage=exactServer
                    }
                }
            }
        }
        if(owner=="fullporner.com"){
            val originals=fullPornerSelectedPlayer(player,selected,playerPage)
            if(originals.isNotEmpty())return originals
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
            runCatching{BrowserPageFetch.html(context,playerPage,
                playerReferer=if(playerPage==link)selected else link)}.getOrNull()
        else null
        if(!html.isNullOrBlank()){
            val rendered=Jsoup.parse(html,playerPage)
            val css="main .video-player video[src],video[src][data-duration]"+
                (if(owner=="uncensoredhentai.xxx")
                    ",#player video[src],#player video source[src],"+
                    ".jwplayer video[src],.jwplayer video source[src]"
                else "")
            val media=(mediaElements(rendered,playerPage,css)+
                jsSources(rendered,playerPage)).distinctBy{it.url}
            if(media.isNotEmpty())return media
        }
        // The FullPorner player advertises THREE real quality variants
        // at xiaoshenke.net/vid/<opaque-id> (no .mp4 extension). Read only
        // the exact selected player's <source type=video/mp4> elements.
        if(owner=="fullporner.com"){
            val rendered=html?.let{Jsoup.parse(it,playerPage)}
            // BrowserPageFetch serializes only the selected HTML5
            // currentSrc into <main><div class=video-player><video src>.
            // The original <source> elements are not preserved there.
            val verified=rendered?.select(
                "main .video-player video[src]")?.mapNotNull { node->
                val link=https(node.attr("src"),playerPage)
                if(link!=null && FullPornerOriginalSource.verifiedMedia(
                        selected,playerPage,link))
                    Source(link,playerPage)
                else null
            }.orEmpty().distinctBy{it.url}
            if(verified.isNotEmpty())return verified
        }
        // yt-dlp may have a dedicated extractor for the publisher-attested
        // player host, so give it the EXACT iframe rather than a made-up MP4.
        return listOf(Source(playerPage,if(playerPage==link)selected else link))
    }
    /** JavGuru: STREAM buttons belong to the exact selected WordPress post,
     * whereas its only static iframe is a pop-under advertisement. Follow
     * the publisher-attested shortcode and its first-party /searcho/ relay
     * to the final real embed. Never give the watch page or ad to Python.
     */
    internal fun javGuru(context:Context,doc:Document,selected:String):List<Source>{
        val initial=JavGuruOriginalSource.published(selected,doc.outerHtml())
        // Some Android HTTP responses provide a 200 shell without the real
        // selected WordPress STREAM shortcode/inline iframe_url config. The
        // site's own WebView renders that exact chosen post using its normal
        // authorized session. Inspect it only when static parsing found zero
        // actual selected controls; never use the advertising iframe.
        val streams=initial.ifEmpty {
            if(!BrowserPageFetch.publicEligible(selected) &&
                !SiteSession.bound(context,selected))emptyList()
            else runCatching {
                JavGuruOriginalSource.published(selected,
                    BrowserPageFetch.html(context,selected))
            }.getOrDefault(emptyList())
        }
        val found=LinkedHashMap<String,Source>()
        // The owner's MXGS-1452 post publishes VO, SB, DD and JK.
        // Previous take(3) *never even attempted* its final, stable JK
        // original. Preserve LU preference when available, then try JK
        // before unstable SB/other hosters; stay bounded to six official
        // same-post STREAM controls. No unrelated result fallback.
        val order=streams.sortedBy { stream ->
            when(stream.server) {
                "LU" -> 0
                "JK" -> 1
                else -> 2
            }
        }
        for(stream in order.take(6)){
            val wrapper=runCatching{
                Jsoup.connect(stream.url)
                    .userAgent(NativeMediaDownloader.USER_AGENT)
                    .header("Referer",selected)
                    .ignoreHttpErrors(true).followRedirects(false)
                    .timeout(8_000).maxBodySize(350_000)
                    .apply{
                        NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                        SiteSession.cookie(context,stream.url)
                            ?.takeIf{it.isNotBlank()}?.let{header("Cookie",it)}
                    }.execute()
            }.getOrNull()?:continue
            if(wrapper.statusCode() !in 200..299)continue
            val next=JavGuruOriginalSource.nextIframe(
                stream.url,wrapper.body())?:continue
            val response=runCatching{
                Jsoup.connect(next)
                    .userAgent(NativeMediaDownloader.USER_AGENT)
                    .header("Referer",stream.url)
                    .ignoreHttpErrors(true).followRedirects(true)
                    .timeout(8_000).maxBodySize(700_000)
                    .apply{
                        NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                        SiteSession.cookie(context,next)
                            ?.takeIf{it.isNotBlank()}?.let{header("Cookie",it)}
                    }.execute()
            }.getOrNull()?:continue
            if(response.statusCode() !in 200..299)continue
            val embed=response.url().toString()
            if(!JavGuruOriginalSource.selectedEmbed(stream.server,embed))continue
            // The verified selected LuluStream hoster publishes its complete
            // HLS playlist in packed JWPlayer source configuration, not in
            // an HTML video tag. Parse that single source before considering
            // the hoster as an extractor fallback.
            val hls=JavGuruOriginalSource.selectedHls(embed,response.body())
            if(hls!=null){
                // This authenticated-by-the-selected-post manifest has a
                // short-lived signature. Returning it immediately prevents
                // probing unrelated fallback hosters for up to 32 seconds
                // BEFORE the first original fragment is requested.
                return listOf(Source(hls,embed))
            }
            val player=Jsoup.parse(response.body(),embed)
            // Do not promote generic embeds, ad players or script URL
            // strings as the selected original. Only an unambiguous video
            // inside the final hoster's real player is a direct-media source.
            val exact=mediaElements(player,embed,
                "#player video[src],#player video source[src],"+
                "#video-player video[src],#video-player video source[src],"+
                ".jwplayer video[src],.jwplayer video source[src]")
                .distinctBy{it.url}.takeIf{it.size==1}.orEmpty()
            if(exact.isNotEmpty()){
                for(media in exact)
                    found.putIfAbsent(media.url,media)
            }else{
                // The exact publisher-authored external embed is a supported
                // yt-dlp hoster candidate, NOT the unsupported jav.guru URL.
                found.putIfAbsent(embed,Source(embed,stream.url))
            }
        }
        return found.values.toList()
    }

    /** Roshy uses its site-owned Beeteam368 "Original Stream" player as
     * well as file-host download links. Prefer the actual selected player
     * media; do NOT confuse preview tiles or a hoster HTML page with media.
     */
    internal fun roshy(doc:Document,selected:String):List<Source>{
        val out=LinkedHashMap<String,Source>()
        val mainPlayer=doc.selectFirst(
            "#main-content .is-single-post-main-player [id^=beeteam368_player_]")
        if(mainPlayer!=null){
            val configStart=Regex("""jQuery\(\s*['"]#${Regex.escape(mainPlayer.id())}['"]\s*\)\s*\.beeteam368_pro_player\(\s*""")
            for(script in doc.select("script:not([src])").take(90)){
                val body=script.data()
                val start=configStart.find(body)?:continue
                val config=runCatching {
                    JSONTokener(body.substring(start.range.last+1)).nextValue() as? JSONObject
                }.getOrNull()?:continue
                val raw=config.optString("video_url")
                val link=if(config.optString("video_mode")=="embed"){
                    val frame=Jsoup.parseBodyFragment(raw,selected).selectFirst("iframe[src]")
                    https(frame?.attr("src").orEmpty(),selected)?.takeIf {
                        host(it)=="kamehaus.net" &&
                            Regex("""^/e/[A-Za-z0-9_-]{6,80}/?$""").matches(URI(it).path)
                    }
                } else https(raw,selected)?.takeIf(::isMedia)
                if(link!=null)out[link]=Source(link,selected)
                break
            }
        }
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
        val stream=mediaElements(doc,selected,
            "#main-content .beeteam368-player-wrapper video[src],"+
            "#main-content .beeteam368-player-wrapper source[src],"+
            "#main-content .beeteam368-player-control video[src],"+
            "main .beeteam368-player video[src],main .video-player video[src]")
        // This method is intentionally confined to the selected site's
        // own player. Hoster entries remain fallback transfer candidates.
        return (stream+out.values).distinctBy{it.url}
    }
    /**
     * Preserve genuine published file-host alternatives while forbidding
     * automatic "success" from a Kamehaus HTML iframe. Only resolved
     * media-bearing sources are transferable as an original stream.
     */
    internal fun roshyTransferableCandidates(
        published:List<Source>,verifiedStream:List<Source>
    ):List<Source> =
        (verifiedStream.filter{isMedia(it.url)}+
            published.filter{isMedia(it.url) || isHosterPage(it.url)})
            .distinctBy{it.url}

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
        val published=Mp4HentaiPublishedHost.selectedLinks(doc,selected)
            .map{Source(it,selected)}
        return (player+jsSources(doc,selected)+published).distinctBy{it.url}
    }
    /** HelloPorn /video-<id>-<slug> embeds its selected original in a
     * specific VOE /e/<id> player. The selected page has no HTML5 MP4.
     * Resolve only that published iframe, never unrelated ad/video cards.
     */
    internal fun helloPorn(doc:Document,selected:String):List<Source>{
        if(!Regex("""^/video-[0-9]{1,10}-[a-z0-9][a-z0-9-]{2,240}/?$""",
                RegexOption.IGNORE_CASE).matches(URI(selected).path.orEmpty()))
            return emptyList()
        val out=LinkedHashMap<String,Source>()
        for(frame in doc.select("iframe[src]").take(30)){
            if(frame.closest("#player,.video-player,.player,main,article")==null ||
                frame.closest("aside,.related,.recommendations,.ads,.advertisement,.sidebar")!=null)
                continue
            val link=https(frame.attr("src"),selected)?:continue
            val url=URI(link)
            if(host(link)!="voe.sx" ||
                !Regex("""^/e/[A-Za-z0-9_-]{6,80}/?$""")
                    .matches(url.path.orEmpty()) ||
                !url.rawQuery.isNullOrBlank())continue
            out.putIfAbsent(link,Source(link,selected))
        }
        return out.values.toList().takeIf{it.size==1}.orEmpty()
    }
    /** Resolve a HelloPorn-selected VOE iframe to the media it actually
     * exposes. Passing the iframe page to the generic extractor first returns
     * Unsupported URL and leaves the original queue stuck at zero bytes.
     */
    /**
     * Accept one attributable selected player media URL, not a hosting
     * landing page, recommendation, advertising clip or ambiguous source.
     * Static and browser results must refer to the same exact selected iframe.
     */
    /**
     * A published exact player is an extractor input, not verified media.
     * Restrict fallback to the SAME one selected iframe when its HTML5
     * currentSrc cannot be obtained, never an autoplay advert or sidebar.
     */
    internal fun selectedExtractorFrame(selected:String,player:String):Boolean {
        val page=runCatching{URI(selected)}.getOrNull()?:return false
        val frame=runCatching{URI(player)}.getOrNull()?:return false
        if(page.scheme!="https" || frame.scheme!="https" ||
            page.port!=-1 || frame.port!=-1 || frame.userInfo!=null ||
            !VideoSiteProfiles.isVideoPage(selected) ||
            !WebDiscovery.isUrl(player))return false
        val path=frame.path.orEmpty()
        return when(host(selected)){
            "javideo.net" -> (
                // Live Play-click evidence: the selected publisher now
                // injects play.javideo.net inside its active player. The
                // former Streamtape-only rule rejected the REAL iframe.
                (host(player)=="play.javideo.net" &&
                    Regex("""^/(?:embed|player)/[A-Za-z0-9_-]{5,100}/?$""")
                        .matches(path) &&
                    frame.rawQuery.isNullOrBlank() &&
                    frame.rawFragment==null) ||
                (host(player) in setOf("streamtape.com",
                    "streamtape.to","streamtape.net","streamtape.site",
                    "sbtape.com") &&
                    Regex("""^/(?:e|v|embed)/[A-Za-z0-9_-]{5,100}/?$""")
                        .matches(path)))
            "helloporn.com"->host(player)=="voe.sx" &&
                Regex("""^/e/[A-Za-z0-9_-]{6,80}/?$""")
                    .matches(path) && frame.rawQuery.isNullOrBlank()
            "uncensoredhentai.xxx"->host(player)=="nhplayer.com" &&
                Regex("""^/v/[A-Za-z0-9]{8,40}/?$""")
                    .matches(path) && frame.rawQuery.isNullOrBlank()
            else->false
        }
    }

    private fun selectedIframeMedia(
        context:Context,selected:String,frame:Source
    ):List<Source> {
        val originals=LinkedHashMap<String,Source>()
        val raw=runCatching {
            Jsoup.connect(frame.url)
                .userAgent(NativeMediaDownloader.USER_AGENT)
                .header("Referer",selected)
                .ignoreHttpErrors(true).followRedirects(false)
                .timeout(12_000).maxBodySize(1_800_000)
                .apply{
                    // The selected iframe is on a different publisher-hosted
                    // player domain. If the owner connected that exact host,
                    // reuse ONLY that host's session; never forward the watch
                    // publisher's cookies to a third-party embed or ad.
                    SiteSession.cookie(context,frame.url)?.takeIf{it.isNotBlank()}
                        ?.let{header("Cookie",it)}
                    NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                }
                .execute()
        }.getOrNull()
        if(raw!=null && raw.statusCode() in 200..299 &&
            raw.contentType().contains("html",ignoreCase=true)) {
            val player=Jsoup.parse(raw.body(),frame.url)
            val direct=mediaElements(player,frame.url,
                "#player video[src],#player video source[src],"+
                ".video-player video[src],.video-player video source[src],"+
                "video#mainvideo[src],video#mainvideo source[src],"+
                "video#player[src],video#player source[src]")
            for(media in direct)originals.putIfAbsent(media.url,media)
        }
        if(originals.size!=1) {
            val rendered=runCatching {
                BrowserPageFetch.html(context,frame.url,playerReferer=selected)
            }.getOrNull()
            if(!rendered.isNullOrBlank()) {
                val page=Jsoup.parse(rendered,frame.url)
                for(media in mediaElements(page,frame.url,
                    "main .video-player video[src],"+
                    "main .video-player video source[src]"))
                    originals.putIfAbsent(media.url,media)
            }
        }
        return originals.values.toList().takeIf{it.size==1}.orEmpty()
    }

    internal fun helloPornOriginals(
        context:Context,doc:Document,selected:String
    ):List<Source>{
        val frames=helloPorn(doc,selected).ifEmpty {
            val rendered=runCatching {
                BrowserPageFetch.html(context,selected)
            }.getOrNull()
            rendered?.let{helloPorn(Jsoup.parse(it,selected),selected)}.orEmpty()
        }
        if(frames.size!=1)return emptyList()
        val exact=selectedIframeMedia(context,selected,frames.single())
        return exact.ifEmpty {
            // Exact VOE /e/ selected player may be supported by yt-dlp even
            // when currentSrc is hidden from the Android WebView snapshot.
            frames.filter{selectedExtractorFrame(selected,it.url)}
        }
    }

    /**
     * JAVIDEO displays an unrelated muted advert in initial HTML and puts
     * the selected Streamtape iframe inside the active Play container. Use
     * only that selected iframe and its own video.currentSrc, never the
     * parent's other HTML5 video or a generic yt-dlp hoster-page download.
     */
    internal fun javideoOriginals(
        context:Context,doc:Document,selected:String
    ):List<Source>{
        val specific=".embed-responsive.player:not(.d-none) "
        fun selectedDirect(document:Document):List<Source> =
            mediaElements(document,selected,
                specific+"video[src],"+specific+"video source[src],"+
                specific+"source[type^=video][src]")
                .takeIf{it.size==1}.orEmpty()
        val initial=selectedDirect(doc)
        if(initial.isNotEmpty())return initial
        val initialFrames=javideoSelected(doc,selected)
        val rendered=if(initialFrames.isEmpty())runCatching {
            BrowserPageFetch.html(context,selected)
        }.getOrNull()?.let{Jsoup.parse(it,selected)} else null
        if(rendered!=null){
            val direct=selectedDirect(rendered)
            if(direct.isNotEmpty())return direct
        }
        val frames=if(initialFrames.isNotEmpty())initialFrames
            else rendered?.let{javideoSelected(it,selected)}.orEmpty()
        if(frames.size!=1)return emptyList()
        val exact=selectedIframeMedia(context,selected,frames.single())
        return exact.ifEmpty {
            frames.filter{selectedExtractorFrame(selected,it.url)}
        }
    }

    /**
     * 3DHentaiX previously downloaded a playable OTHER scene from a nearby
     * card/player. Only a SINGLE selected article/main player may publish this
     * scene's source. Never scrape unrelated media or global script URLs.
     */
    internal fun threeDHentaiSelected(doc:Document,selected:String):List<Source>{
        if(host(selected)!="3dhentaix.com" ||
            !VideoSiteProfiles.isVideoPage(selected))return emptyList()
        val controls=doc.select("main .video-player,main #player,article .video-player,article #player")
            .filter{it.closest("aside,.related,.recommendations,.suggestions,"+
                ".ads,.advertisement,.banner,.sidebar,.more-videos,"+
                ".recommended-videos,[data-ad]")==null}
        // Nested wrappers can refer to one player; identify the innermost
        // actual video-bearing container and reject multiple independent ones.
        val players=controls.filter{it.select("video[src],video source[src]").isNotEmpty()}
            .filter{it.select("main .video-player,main #player,article .video-player,article #player")
                .none{nested->nested!==it &&
                    nested.select("video[src],video source[src]").isNotEmpty()}}
        if(players.size!=1)return emptyList()
        val isolated=Jsoup.parse("<main><div class='video-player'>"+
            players.single().select("video[src],video source[src]")
                .joinToString(""){it.outerHtml()}+"</div></main>",selected)
        val sources=mediaElements(isolated,selected,
            "main .video-player video[src],main .video-player video source[src]",
            minimumRuntime=true)
        // Competing media URLs cannot be attributed to the selected work.
        return sources.takeIf{it.size==1}.orEmpty()
    }

    /**
     * JAVIDEO loads its user-selected iframe only after its own Play control.
     * An unrelated muted autoplay advert also appears in the DOM; it must
     * never become the downloaded original. Accept only recognized embed
     * routes under the specific selected player container.
     */
    internal fun javideoSelected(doc:Document,selected:String):List<Source>{
        if(host(selected)!="javideo.net" ||
            !VideoSiteProfiles.isVideoPage(selected))return emptyList()
        val out=LinkedHashMap<String,Source>()
        for(frame in doc.select("main .video-player iframe[src],"+
            ".embed-responsive.player:not(.d-none) iframe[src]").take(10)){
            val link=https(frame.attr("src"),selected)?:continue
            val h=host(link)
            if(!selectedExtractorFrame(selected,link))continue
            out.putIfAbsent(link,Source(link,selected))
        }
        // Never substitute a related card, an advert, or a different server
        // if more than one distinct player claims to be the selected one.
        return out.values.toList().takeIf{it.size==1}.orEmpty()
    }

    internal fun selectedStatic(doc:Document,selected:String,owner:String):List<Source>{
        if(owner=="3dhentaix.com")return threeDHentaiSelected(doc,selected)
        if(owner=="javideo.net")return javideoSelected(doc,selected)
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
        val published=if(owner=="hentaibros.net"){
            // Live HentaiBros posts use <div id="wpfp_3235"
            // data-item="{...sources...}"> in the selected video-player,
            // NOT a .flowplayer element. The previous selector discarded
            // the actual official MP4 and then reported "no original".
            val players=doc.select(".video-player [data-item]").filter {
                (it.id().matches(Regex("""wpfp_[0-9]{1,10}""")) ||
                    it.hasClass("flowplayer")) &&
                it.closest("aside,.related,.recommendations,.ads,.sidebar,"+
                    ".more-videos,.recommended-videos") == null
            }
            // Multiple independent configured players may include adverts
            // or unrelated recommendations. Never guess which one is selected.
            val sources=players.singleOrNull()?.attr("data-item")?.let {
                runCatching { JSONObject(it).optJSONArray("sources") }.getOrNull()
            }
            (0 until minOf(sources?.length()?:0,10)).mapNotNull { index ->
                val raw=sources?.optJSONObject(index)?.optString("src").orEmpty()
                val link=https(raw,selected)?.takeIf(::isMedia)
                link?.let { Source(it,selected) }
            }
        } else if(gated)emptyList() else jsSources(doc,selected)
        // When the selected WordPress player itself publishes its original,
        // do not append other HTML5 videos from the same wrapper. Live pages
        // may load separate playable previews/advertising elements here.
        // Returning both let TransferService fall through to a wrong video
        // when the authoritative original transfer failed.
        val selectedSources=if(owner=="hentaibros.net" && published.isNotEmpty())
            published else published+direct
        return selectedSources.distinctBy{it.url}
    }
    fun resolve(context:Context,selected:String,doc:Document):
            List<PublisherOriginalExtractors.Candidate>{
        val owner=host(selected)
        if(owner !in owners || !VideoSiteProfiles.isVideoPage(selected))
            return emptyList()
        val originals=when(owner){
            "jav.guru"->javGuru(context,doc,selected)
            "fullporner.com","hqpornerxxx.com","uncensoredhentai.xxx" ->
                fromPlayer(context,doc,selected,owner)
            "roshy.tv"->{
                val initial=roshy(doc,selected)
                val frames=initial.filter{host(it.url)=="kamehaus.net"}
                // Roshy's actual selected Kamehaus /e/<id> exposes HLS4 in
                // packed JWPlayer text. yt-dlp does NOT support the HTML
                // player URL, so obtain the manifest from that exact player
                // instead. This is text deobfuscation, NEVER JS execution.
                val officialHls=if(frames.size==1 &&
                    RoshyPackedOriginalSource.selectedEmbed(
                        selected,frames.single().url))
                    runCatching {
                        val frame=frames.single()
                        val response=Jsoup.connect(frame.url)
                            .userAgent(NativeMediaDownloader.USER_AGENT)
                            .header("Referer",selected)
                            .ignoreHttpErrors(true).followRedirects(false)
                            .timeout(14_000).maxBodySize(1_500_000)
                            .apply { NetworkRoute.javaProxy(context,selected)
                                ?.let{proxy(it)} }.execute()
                        if(response.statusCode() !in 200..299)null
                        else RoshyPackedOriginalSource.selectedPlaylist(
                            selected,frame.url,response.body())?.let {
                            Source(it,frame.url)
                        }
                    }.getOrNull()
                else null
                // An iframe HTML response is never an original. If a
                // publisher-attested HLS manifest exists, avoid a redundant
                // expensive WebView and generic iframe extractor.
                val verifiedStream=if(officialHls!=null)listOf(officialHls)
                    else if(frames.size==1)
                        selectedIframeMedia(context,selected,frames.single())
                    else emptyList()
                val jsStream=if(frames.isEmpty() && initial.none{isMedia(it.url)})
                    runCatching{
                        val rendered=BrowserPageFetch.html(context,selected)
                        mediaElements(Jsoup.parse(rendered,selected),selected,
                            "main .video-player video[src],"+
                            "main .video-player video source[src]")
                    }.getOrDefault(emptyList())
                else emptyList()
                roshyTransferableCandidates(initial,verifiedStream+jsStream)
            }
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
            "helloporn.com"->{
                // The publisher page includes dozens of unrelated preview
                // videos. Only its one selected VOE iframe may authorize a
                // transfer; generic static video fallback risks wrong files.
                helloPornOriginals(context,doc,selected)
            }
            "javideo.net"->javideoOriginals(context,doc,selected)
            "3dhentaix.com"->{
                // LIVE browser-byte evidence: 3DHentai's HTML5 element can
                // play a third-party 6 MB unrelated advertising MP4. Its
                // existence, duration or mime type CANNOT attest selected
                // content. Resolve the signed first-party post_id + nonce
                // URL only; if that API fails, stop rather than save an ad.
                listOf(Source(ThreeDHentaiSignedOriginal.resolve(
                    context,selected,doc),selected))
            }
            "hentaibros.net"->{
                selectedStatic(doc,selected,owner).takeIf{it.isNotEmpty()}?:
                    runCatching {
                        if(!BrowserPageFetch.publicEligible(selected) &&
                            !SiteSession.bound(context,selected))emptyList()
                        else selectedStatic(
                            Jsoup.parse(BrowserPageFetch.html(context,selected),
                                selected),selected,owner)
                    }.getOrDefault(emptyList())
            }
            "hentaihaven.xxx"->{
                // HentaiHaven now serves a Next.js SERIES page with links to
                // independently downloadable /episode-N pages. Treating
                // /watch/<series> as a video (often titled "4.0 69K")
                // always returned 0 original bytes. Do not silently choose
                // episode one out of a multi-episode collection.
                if(HentaiHavenEpisodeSource.isSeries(selected)){
                    val episodes=HentaiHavenEpisodeSource.episodes(doc,selected)
                    throw IllegalStateException(
                        "HentaiHaven selected a series with "+
                        episodes.size+" episode(s), not a video; choose "+
                        "the exact episode from the series card.")
                }
                // Confirmed live: selected episode's VideoObject contentUrl
                // points to one octopusmanifest.org HLS master, HTTP 200
                // #EXTM3U with two variants. Other MP4 strings in Next.js
                // are previewUrl recommendations, NOT this selected episode.
                if(!HentaiHavenEpisodeSource.isEpisode(selected))
                    emptyList()
                else {
                    val manifest=HentaiHavenEpisodeSource.publishedHls(
                        doc,selected)
                    if(manifest!=null)listOf(Source(manifest,selected))
                    else runCatching {
                        if(!BrowserPageFetch.publicEligible(selected) &&
                            !SiteSession.bound(context,selected))emptyList()
                        else {
                            val rendered=Jsoup.parse(
                                BrowserPageFetch.html(context,selected),selected)
                            HentaiHavenEpisodeSource.publishedHls(
                                rendered,selected)
                                ?.let{listOf(Source(it,selected))}
                                .orEmpty()
                        }
                    }.getOrDefault(emptyList())
                    // No generic article/video fallback: the Next.js page
                    // publishes unrelated recommendation preview MP4s.
                }
            }
            else->emptyList()
        }
        return originals.distinctBy{it.url}.map{
            PublisherOriginalExtractors.Candidate(it.url,it.referer,owner)
        }
    }
}
