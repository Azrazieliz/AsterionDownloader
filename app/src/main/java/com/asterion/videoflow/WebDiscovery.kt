package com.asterion.videoflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

data class VideoHit(val title:String, val url:String)

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
        .userAgent("Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36")
        .timeout(18000).followRedirects(true).maxBodySize(2500000).get()
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


    /** Follow only numbered pages of the exact performer profile. */
    fun findWatchPornArtist(address:String,full:Boolean):List<VideoHit>{
        val first=URI(address)
        require(first.scheme=="https" && first.host=="watchporn.to" &&
            Regex("^/models/[a-zA-Z0-9_-]+/?$").matches(first.path)) {
            "Expected a WatchPorn performer URL"
        }
        val base="https://watchporn.to"+first.path.trimEnd('/')+"/"
        val visited=HashSet<String>()
        val videos=linkedMapOf<String,VideoHit>()
        var current=base
        for(page in 0 until (if(full)80 else 3)){
            if(!visited.add(current))break
            val doc=open(current)
            require(doc.location().startsWith(base)){"Performer page redirected"}
            val root=URI(doc.location())
            for(a in doc.select("a[href*=/video/]").take(700)){
                val url=runCatching{root.resolve(a.attr("href")).toString()}.getOrNull()?:continue
                val u=runCatching{URI(url)}.getOrNull()?:continue
                if(u.scheme!="https" || u.host!="watchporn.to" ||
                    !Regex("^/video/[0-9]+/[^?#]*$").matches(u.path))continue
                val title=a.attr("title").ifBlank{
                    a.selectFirst("img[alt]")?.attr("alt").orEmpty()
                }.ifBlank{a.text()}.ifBlank{"WatchPorn video"}
                videos.putIfAbsent(url,VideoHit(title.take(180),url))
                if(videos.size>=3000)return videos.values.toList()
            }
            val next=doc.selectFirst(".wp-pag__next a[href]")?.absUrl("href").orEmpty()
            val target=runCatching{URI(next)}.getOrNull()
            if(target?.scheme!="https" || target.host!="watchporn.to" ||
                !Regex("^"+Regex.escape(first.path.trimEnd('/'))+"/[0-9]+/?$")
                    .matches(target.path) || next in visited)break
            current=next
        }
        return videos.values.toList()
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
