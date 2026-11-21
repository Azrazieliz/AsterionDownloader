package com.asterion.videoflow

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** Exact PornBuz article -> publisher-labelled, selected external player.
 * Other embeds, sidebar links, advertisements and related scenes never
 * become download candidates. The hoster itself must still resolve a complete
 * original; this parser is not a successful download receipt.
 */
internal object PornBuzOriginalSource {
    private val selectedArticle=Regex(
        """(?i)^/video/[a-z0-9][a-z0-9-]{7,190}-[0-9]{3,10}/?$""")
    private val playerId=Regex(
        """^/(?:v|e|embed)/[A-Za-z0-9_-]{6,100}(?:/[A-Za-z0-9_.%-]{3,250})?/?$""")
    fun owns(raw:String):Boolean {
        val u=runCatching{URI(raw)}.getOrNull()?:return false
        val host=u.host.orEmpty().lowercase(Locale.ROOT)
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            (host=="pornbuz.com" || host=="www.pornbuz.com") &&
            selectedArticle.matches(u.path.orEmpty())
    }
    /** The selected article displays a genuine primary JWPlayer embed
     * inside its video-player area. Sidebar trailers and the independent
     * Server 2 button are excluded. Keep the existing Server 2 parser as an
     * unchanged fallback when this primary source cannot be verified.
     */
    fun selectedMainPlayer(article:String,html:String):String? {
        if(!owns(article))return null
        val doc=Jsoup.parse(html,article)
        val frames=doc.select(
            "article .video-player-area .video-player .responsive-player iframe[src]")
            .filter{it.closest("aside,.related,.recommended,.recommendations,"+
                ".ads,.advertisement,.sidebar,[data-ad]")==null}
            .mapNotNull{frame->
                frame.absUrl("src").takeIf(::isSelectedMainFrame)
            }.distinct()
        return frames.singleOrNull()
    }

    internal fun isSelectedMainFrame(raw:String):Boolean {
        val uri=runCatching{URI(raw)}.getOrNull()?:return false
        return uri.scheme=="https" && uri.userInfo==null && uri.port==-1 &&
            uri.host.orEmpty().lowercase(Locale.ROOT)=="player.watchstreamus.com" &&
            uri.rawQuery.isNullOrBlank() && uri.rawFragment==null &&
            Regex("""^/embed/[A-Za-z0-9_-]{5,150}/?$""")
                .matches(uri.path.orEmpty())
    }

    /** VK_PLAYBACK is the active JWPlayer's HLS, not VK_ADS and not the
     * separately loaded thumbnail trailers. Parse only its JSON config.
     */
    fun selectedHls(frame:String,html:String):String? {
        if(!isSelectedMainFrame(frame))return null
        val doc=Jsoup.parse(html,frame)
        val candidates=doc.select("script:not([src])").mapNotNull{script->
            val js=script.data().ifBlank{script.html()}
            val marker=Regex("""(?:window\.)?VK_PLAYBACK\s*=\s*""")
                .find(js)?:return@mapNotNull null
            val config=runCatching {
                org.json.JSONTokener(js.substring(marker.range.last+1))
                    .nextValue() as? org.json.JSONObject
            }.getOrNull()?:return@mapNotNull null
            if(config.optString("format")!="hls")return@mapNotNull null
            val value=config.optString("src").trim()
                .takeIf{it.isNotBlank()}?:return@mapNotNull null
            val url=runCatching{URI(frame).resolve(value)}.getOrNull()
                ?:return@mapNotNull null
            if(isSelectedManifest(url))url.toString() else null
        }.distinct()
        return candidates.singleOrNull()
    }

    private fun isSelectedManifest(url:URI):Boolean =
        url.scheme=="https" && url.userInfo==null && url.port==-1 &&
            url.host.orEmpty().lowercase(Locale.ROOT)=="player.watchstreamus.com" &&
            url.fragment==null &&
            url.path.orEmpty().matches(Regex("""(?i)^/[^?#]{5,1500}\.m3u8$""")) &&
            !url.path.orEmpty().contains("preview",true) &&
            !url.path.orEmpty().contains("trailer",true)

    /** Publisher master can legitimately advertise 480p/720p/1080p.
     * The former singleOrNull() discarded ALL three variants, causing the
     * unverified Server 2 teaser fallback even when the real HLS existed.
     * Only same-player HTTPS .m3u8 children named in this master qualify.
     */
    internal fun rankedChildManifests(master:String,manifest:String):List<String>{
        if(!manifest.startsWith("#EXTM3U"))return emptyList()
        val lines=manifest.lines().map(String::trim)
        val found=LinkedHashMap<String,Long>()
        for(i in 0 until lines.size-1){
            val line=lines[i]
            if(!line.startsWith("#EXT-X-STREAM-INF:"))continue
            val child=lines[i+1].takeIf{
                it.isNotBlank() && !it.startsWith("#")
            }?:continue
            val uri=runCatching{URI(master).resolve(child)}.getOrNull()
                ?:continue
            if(!isSelectedManifest(uri))continue
            val bandwidth=Regex("""(?:^|,)BANDWIDTH=([0-9]{1,12})(?:,|$)""")
                .find(line.substringAfter(':'))?.groupValues?.get(1)
                ?.toLongOrNull()?:0L
            found.putIfAbsent(uri.toString(),bandwidth)
        }
        return found.entries.sortedWith(
            compareByDescending<Map.Entry<String,Long>>{it.value}
                .thenBy{it.key}
        ).take(8).map{it.key}
    }

    // Compatibility for single-rendition callers and existing fixtures.
    fun childManifest(master:String,manifest:String):String? =
        rankedChildManifests(master,manifest).singleOrNull()

    fun finalizedFullRuntimeSeconds(manifest:String):Double? {
        if(!manifest.startsWith("#EXTM3U") ||
            manifest.lines().count{it.trim()=="#EXT-X-ENDLIST"}!=1)return null
        val count=manifest.lines().count{
            it.trim().isNotBlank() && !it.trim().startsWith("#")
        }
        val durations=Regex("""#EXTINF:([0-9]+(?:\.[0-9]+)?)""")
            .findAll(manifest).mapNotNull{it.groupValues[1].toDoubleOrNull()}
            .toList()
        if(count<10 || durations.size!=count)return null
        return durations.sum().takeIf{it>=60.0 && it<=24.0*3600.0}
    }

    /** Fetch ONLY selected text playlists, never media fragments. The actual
     * downloader still verifies the full received file. This defeats the
     * observed 20-second teaser being incorrectly saved as the original.
     */
    fun verifiedFullHls(context:android.content.Context,
                        selected:String,frame:String,master:String):Boolean =
        verifiedFullHlsUrl(context,selected,frame,master)!=null

    /** Return the *verified* full-duration media playlist, not an HLS
     * master where yt-dlp could independently select an unverified teaser.
     * Longest/finalized first-party playlists are checked by actual EXTINF.
     */
    fun verifiedFullHlsUrl(context:android.content.Context,
                           selected:String,frame:String,master:String):String? {
        if(!owns(selected) || !isSelectedMainFrame(frame) ||
            !runCatching{isSelectedManifest(URI(master))}
                .getOrDefault(false))return null
        fun manifest(url:String,referer:String):String? {
            val response=runCatching {
                org.jsoup.Jsoup.connect(url)
                    .userAgent(NativeMediaDownloader.USER_AGENT)
                    .header("Referer",referer)
                    .header("Accept-Encoding","identity")
                    .ignoreHttpErrors(true).ignoreContentType(true)
                    .followRedirects(false).timeout(13_000)
                    .maxBodySize(230_000).apply{
                        NetworkRoute.javaProxyForSelected(
                            context,selected,url)?.let{proxy(it)}
                        com.azrael.galleryflow.SiteSession.cookie(context,url)
                            ?.takeIf{it.isNotBlank()}?.let{header("Cookie",it)}
                    }.execute()
            }.getOrNull()?:return null
            if(response.statusCode()!=200)return null
            return response.body().takeIf{it.startsWith("#EXTM3U")}
        }
        val text=manifest(master,frame)?:return null
        val children=rankedChildManifests(master,text)
        if(children.isEmpty())
            return master.takeIf{finalizedFullRuntimeSeconds(text)!=null}
        for(child in children){
            val mediaPlaylist=manifest(child,master)?:continue
            if(finalizedFullRuntimeSeconds(mediaPlaylist)!=null)
                return child
        }
        // Every rendition must fail closed if it is missing, unfinalized,
        // or a short preview. Preserve the old server route for diagnostic
        // fallback only; never assert success from player metadata alone.
        return null
    }

    fun published(article:String,html:String):List<String> {
        if(!owns(article))return emptyList()
        val doc=Jsoup.parse(html,article)
        val sources=doc.select("a[href]").asSequence().filter{anchor->
            anchor.closest("aside,.related,.recommended,.recommendations,"+
                ".ads,.advertisement,.sidebar,[data-ad]")==null &&
            listOf(anchor.text(),anchor.attr("title"),
                anchor.attr("aria-label")).any{label->
                label.trim().matches(Regex("""(?i)^server\s*2$"""))
            }
        }.mapNotNull{anchor->
            val target=runCatching{URI(anchor.absUrl("href"))}.getOrNull()
                ?:return@mapNotNull null
            val host=target.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")
            if(target.scheme=="https" && target.userInfo==null &&
                target.port==-1 && target.rawFragment==null &&
                host in setOf("streamtape.com","streamtape.to",
                    "streamtape.net","streamtape.site","sbtape.com") &&
                playerId.matches(target.path.orEmpty()))
                target.toString() else null
        }.distinct().toList()
        // An ambiguous choice of unrelated player URLs cannot establish
        // which source belongs to the selected work.
        return if(sources.size==1)sources else emptyList()
    }
}
