package com.azrael.pixivdumpsync

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Parsing and ranking are independent of Android and never remove user query terms. */
enum class SearchProviderKind { SEARXNG, HTML }
data class SearchProvider(val kind:SearchProviderKind,val url:String,val label:String)
data class RankedHit(val title:String,val url:String,val source:String,
    val description:String="",val score:Int=0,val thumbnail:String?=null)
data class SearchOutcome(val hits:List<RankedHit>,val checked:Int,val failures:List<String>)

object SearchRanking {
    private val tracking=setOf("fbclid","gclid","ref","ref_src","source","mc_cid","mc_eid")
    fun validUrl(value:String):Boolean { return try {
        val uri=URI(value.trim())
        val host=uri.host?.lowercase(Locale.ROOT)?:return false
        if(uri.scheme !in setOf("http","https") || uri.userInfo!=null)return false
        if(host=="localhost"||host.endsWith(".localhost")||host.endsWith(".local")||
            host.endsWith(".internal")||host=="0.0.0.0"||host=="::1"||
            host.startsWith("127.")||host.startsWith("10.")||
            host.startsWith("192.168.")||host.startsWith("169.254.")||
            (host.startsWith("172.")&&host.split('.').getOrNull(1)?.toIntOrNull() in 16..31))
            return false
        true
    } catch(_:Exception){false} }
    fun parseProviders(lines:String):List<SearchProvider> {
        val parsed=ArrayList<SearchProvider>()
        for(raw in lines.lineSequence()){
            val line=raw.trim()
            if(line.isEmpty()||line.startsWith("#"))continue
            val parts=line.split('|',limit=3)
            require(parts.size>=2){"Source format: searxng|URL or html|URL-containing-{query}"}
            val kind=when(parts[0].trim().lowercase(Locale.ROOT)){
                "searxng","index"->SearchProviderKind.SEARXNG
                "html","site"->SearchProviderKind.HTML
                else->throw IllegalArgumentException("Unknown source type: ${parts[0].trim()}")
            }
            val address=parts[1].trim().trimEnd('/')
            val checked=address.replace("{query}","sample").replace("{name}","sample")
            require(validUrl(checked)){"Invalid source URL: $address"}
            if(kind==SearchProviderKind.HTML)
                require("{query}" in address||"{name}" in address){
                    "HTML search URL must contain {query}"}
            val label=parts.getOrNull(2)?.trim()?.take(60)
                ?.takeIf(String::isNotBlank)?:URI(checked).host.orEmpty()
            parsed.add(SearchProvider(kind,address,label))
        }
        return parsed.distinctBy{it.kind to it.url}
    }
    fun canonicalKey(raw:String):String { return try {
        val uri=URI(raw.trim())
        val host=uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")?:return raw.trim()
        val path=uri.rawPath.orEmpty().trimEnd('/').ifEmpty{"/"}
        // Don't rewrite the actual URL sent to the downloader. This is only a deduplication key.
        val filtered=uri.rawQuery.orEmpty().split('&').filter{it.isNotBlank()}.filterNot{
            val key=it.substringBefore('=').lowercase(Locale.ROOT)
            key.startsWith("utm_")||key in tracking
        }.sorted().joinToString("&")
        "${uri.scheme?.lowercase(Locale.ROOT)}://$host$path"+
            if(filtered.isNotBlank())"?$filtered" else ""
    }catch(_:Exception){raw.trim()} }
    /** Search results are video pages, not browser searches, profiles or categories. */
    fun isVideoUrl(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val path=uri.path.orEmpty().lowercase(Locale.ROOT)
        val host=uri.host.orEmpty().lowercase(Locale.ROOT)
        val query=uri.query.orEmpty().lowercase(Locale.ROOT)
        if(isIndexPage(url))return false // Torrent detail pages are not video files.
        if(listOf("reverso.net","reverso.com","wiktionary.org","wordreference.com",
                "dictionary.com","linguee.com","deepl.com","thesaurus.com")
                .any{host==it||host.endsWith("."+it)})return false
        if(path.matches(Regex("(?i).*/(?:search|category|categories|tag|tags|user|users|channel|channels|playlist|playlists)(?:/.*)?$")))return false
        if(host=="hanime.tv" &&
            (path.startsWith("/hentai-videos/") || path.startsWith("/hentai/")))return true
        if(host=="hentaihaven.xxx" &&
            (path.startsWith("/watch/") || path.startsWith("/video/")))return true
        if(host=="hentai.tv" &&
            (path.startsWith("/series/")||path.startsWith("/episode/")||path.startsWith("/watch/")||
             path.startsWith("/anime/")||path.startsWith("/hentai/")))return true
        if(host.contains("xvideos.com") && Regex("^/video(?:[._0-9]|/)").containsMatchIn(path))return true
        if(host.contains("pornhub.com") && path=="/view_video.php")
            return query.contains("viewkey=")
        if(host.contains("youtube.com")&&path=="/watch")
            return query.contains("v=")
        if(host=="youtu.be")return path.length>2
        if(host.contains("vimeo.com")&&Regex("^/\\d+").containsMatchIn(path))return true
        return Regex("(?i)(/(?:watch|watch-video|watching|video|videos|view_video|viewkey|movie|movies|shorts|reel|reels|clip|clips|scene|scenes|embed|post|posts)(?:[/_-]|$)|\\.(?:mp4|webm|m3u8|mpd)$)")
            .containsMatchIn(path)
    }
    /** Torrent-index detail pages can be discovered, but cannot be queued as videos. */
    fun isIndexPage(url:String):Boolean {
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        val host=uri.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        val path=uri.path.orEmpty()
        return (host in setOf("nyaa.si","sukebei.nyaa.si","sukebei.nyaa.net") &&
                Regex("^/view/\\d+/?$").matches(path)) ||
            (host in setOf("thepiratebay.org","thepiratebay10.org") &&
                Regex("^/description/\\d+/?$").matches(path)) ||
            (host in setOf("1337x.to","1337x.st") &&
                Regex("^/torrent/\\d+/[^/]+/?$").matches(path))
    }
    fun deduplicateIndex(query:String,hits:List<RankedHit>):List<RankedHit> {
        val stored=linkedMapOf<String,RankedHit>()
        for(hit in hits){
            if(!validUrl(hit.url) ||
                !(isVideoUrl(hit.url)||isIndexPage(hit.url)))continue
            val score=relevance(query,hit.title,hit.description)
            if(score==0)continue
            val normalized=hit.copy(score=score)
            val key=canonicalKey(hit.url)
            if(stored[key]?.score?.let{it>=score}==true)continue
            stored[key]=normalized
        }
        return stored.values.sortedByDescending{it.score}
    }
    /** Require most user terms in the title; descriptions cannot rescue junk titles. */
    fun relevance(query:String,title:String,description:String):Int {
        val words=query.trim().lowercase(Locale.ROOT).split(Regex("\\s+"))
            .filter{it.length>1}.distinct()
        if(words.isEmpty())return 0
        val head=title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ")
        val body=description.lowercase(Locale.ROOT)
        val titleMatches=words.count{head.contains(it)}
        val required=when(words.size){1->1;2->2;3->3;4->4;else->kotlin.math.ceil(words.size*0.8).toInt()}
        if(titleMatches<required)return 0
        val phrase=query.trim().lowercase(Locale.ROOT)
        return titleMatches*30+
            (if(head.contains(phrase))150 else 0)+
            words.count{body.contains(it)}*3
    }
    fun deduplicate(query:String,hits:List<RankedHit>):List<RankedHit> {
        val unique=linkedMapOf<String,RankedHit>()
        for(hit in hits){
            if(!validUrl(hit.url)||!isVideoUrl(hit.url))continue
            val normalized=hit.copy(score=relevance(query,hit.title,hit.description))
            if(normalized.score==0)continue
            val key=canonicalKey(hit.url)
            val previous=unique[key]
            if(previous==null || normalized.score>previous.score)unique[key]=normalized
        }
        return unique.values.sortedWith(compareByDescending<RankedHit>{it.score}
            .thenBy{it.title.lowercase(Locale.ROOT)}).toList()
    }
}
