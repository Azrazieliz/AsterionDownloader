package com.asterion.videoflow

import org.jsoup.Jsoup
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
            u.toASCIIString().substringBefore('#')
        }
    } catch(_:Exception){null}
    fun isVideoPage(address:String):Boolean =
        mediaFiles.containsMatchIn(address) || videoPaths.any { it.containsMatchIn(address) }
    private fun open(url:String):Document=Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        .timeout(18000).followRedirects(true).maxBodySize(2500000)
        .apply{SiteSession.cookie(url)?.let{header("Cookie",it)}}.get()
    fun findOnPage(address:String,limit:Int=15):List<VideoHit> {
        require(isUrl(address)){"Invalid public HTTP(S) page"}
        val root=open(address)
        val initialHost=URI(address).host.lowercase().removePrefix("www.")
        val found=linkedMapOf<String,VideoHit>()
        fun add(raw:String,title:String){
            val url=runCatching{URI(root.location()).resolve(raw).toString()}.getOrNull()?:return
            val normalized=normalize(url)?:return
            val host=URI(normalized).host.lowercase().removePrefix("www.")
            if(host!=initialHost&&!host.endsWith(".$initialHost"))return
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
    fun page(address:String,root:String):VideoPage {
        require(isUrl(address)&&isUrl(root)){"Invalid source URL"}
        val host=URI(root).host.lowercase().removePrefix("www.")
        val target=URI(address).host.lowercase().removePrefix("www.")
        require(target==host || target.endsWith(".$host")){"Source pagination crossed providers"}
        val doc=open(address)
        val actual=URI(doc.location())
        val actualHost=actual.host.orEmpty().lowercase().removePrefix("www.")
        require(actualHost==host || actualHost.endsWith(".$host")){"Unexpected source redirect"}
        val found=linkedMapOf<String,VideoHit>()
        for(a in doc.select("a[href]").take(1500)){
            val url=normalize(a.absUrl("href"))?:continue
            val uri=URI(url)
            val linkHost=uri.host.lowercase().removePrefix("www.")
            if(linkHost!=host && !linkHost.endsWith(".$host"))continue
            if(!isVideoPage(url))continue
            val title=a.attr("title").ifBlank{a.text()}
                .ifBlank{a.selectFirst("img[alt]")?.attr("alt").orEmpty()}
                .ifBlank{"Video"}.take(180)
            val thumbnail=a.selectFirst("img")?.let{img->
                listOf("data-src","data-original","src")
                    .map{img.absUrl(it)}.firstOrNull{isUrl(it)}
            }
            found.putIfAbsent(url,VideoHit(title,url,thumbnail))
            if(found.size>=350)break
        }
        val rootPath=URI(root).path.trimEnd('/')
        val explicit=doc.selectFirst("a[rel=next][href],.pagination a.next[href],"+
            "a.next[href],link[rel=next][href]")?.absUrl("href")
        val next=(explicit?:com.azrael.pixivdumpsync.SourceIndexParser
            .next(doc,root,address))
            ?.let(::normalize)?.takeIf{candidate->
                val u=URI(candidate)
                val h=u.host.lowercase().removePrefix("www.")
                (h==host || h.endsWith(".$host")) &&
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
    fun watchPornArtistPage(profile:String,address:String):VideoPage {
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
        val doc=open(address)
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

    fun directMedia(address:String):String? {
        require(isUrl(address)){"Invalid public HTTP(S) URL"}
        if(mediaFiles.containsMatchIn(address))return address
        val page=open(address)
        if(URI(address).host.orEmpty().endsWith("watchporn.to")){
            watchPornDirect(page.html())?.let{return it}
        }
        for(node in page.select("video[src],video source[src],source[src],meta[property=og:video],meta[property=og:video:url],meta[itemprop=contentUrl]")){
            val source=node.attr("abs:src").ifBlank{node.attr("content")}
            val resolved=runCatching{URI(page.location()).resolve(source).toString()}.getOrNull()?:continue
            if(isUrl(resolved)&&mediaFiles.containsMatchIn(resolved))return resolved
        }
        return null
    }
}
