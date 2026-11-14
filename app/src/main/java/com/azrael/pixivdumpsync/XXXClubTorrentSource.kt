package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * XXXClub publishes torrent detail entries, NOT HTML5 stream-watch pages.
 * Its exact /torrents/details/<id> pages link to first-party .torrent
 * descriptors via /torrents/download/<40-hex>. Preserve the existing native
 * torrent queue and quarantine instead of feeding detail HTML into yt-dlp.
 */
internal object XXXClubTorrentSource {
    private val detail=Regex("""^/torrents/details/([0-9]{1,16})/?$""")
    private val descriptor=Regex("""^/torrents/download/[0-9a-fA-F]{40}/?$""")
    fun isDetail(url:String):Boolean {
        val u=runCatching{URI(url)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="xxxclub.to" &&
            detail.matches(u.path.orEmpty())
    }
    fun isDescriptor(url:String):Boolean {
        val u=runCatching{URI(url)}.getOrNull()?:return false
        return u.scheme=="https" && u.userInfo==null &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="xxxclub.to" &&
            descriptor.matches(u.path.orEmpty())
    }
    fun entryId(url:String):String? =
        if(isDetail(url))detail.matchEntire(URI(url).path)?.groupValues?.get(1)
        else null

    fun original(html:String,detailUrl:String):String? {
        if(!isDetail(detailUrl))return null
        val doc=Jsoup.parse(html,detailUrl)
        val options=doc.select("a[href]")
        for(link in options){
            val raw=link.attr("href")
            if(raw.startsWith("magnet:?xt=urn:btih:",true) &&
                raw.length in 30..6000)return raw
            val candidate=runCatching{URI(detailUrl).resolve(raw).toString()}
                .getOrNull()?:continue
            if(isDescriptor(candidate))return candidate
            if(candidate.startsWith("https://") &&
                runCatching{URI(candidate).host.orEmpty().removePrefix("www.")=="xxxclub.to"}
                    .getOrDefault(false) &&
                URI(candidate).path.orEmpty().endsWith(".torrent",true))
                return candidate
        }
        return null
    }
}
