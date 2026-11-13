package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Publisher-specific *catalog* parsing. A listing card is never a verified
 * downloadable video. Use the actual article watch URL for VideoFlow, and
 * retain failures when an upstream site exposes only an age/JS shell.
 */
internal object AdultProviderListings {
    private val hosts=setOf("jav.guru","cums.net","archivebate.com")

    private fun host(url:String)=runCatching {
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    fun owns(base:String,area:String):Boolean =
        area=="ADULT" && host(base) in hosts

    internal fun isListingPage(source:String,candidate:String):Boolean {
        if(!SourceIndexParser.safe(candidate) ||
            !SourceIndexParser.sameSite(source,candidate))return false
        return when(host(source)) {
            "jav.guru","cums.net" -> VideoSiteProfiles.isVideoPage(candidate)
            "archivebate.com" -> runCatching {
                val path=URI(candidate).path.orEmpty()
                Regex("""^/watch/[0-9]{1,16}/?$""").matches(path)
            }.getOrDefault(false)
            else -> false
        }
    }

    private fun title(anchor:Element):String {
        val parent=anchor.closest(
            "article,.post,.video-item,.video-card,.video-block,.video-thumb,"+
            ".video-box,.thumb,.item,.card,.entry,.grid-item")
        return listOf(
            anchor.attr("title"),
            parent?.selectFirst("h2,h3,h4,.entry-title,.video-title,.title")?.text().orEmpty(),
            anchor.selectFirst("img[alt]")?.attr("alt").orEmpty(),
            anchor.text(),
            parent?.selectFirst("img[alt]")?.attr("alt").orEmpty()
        ).map{it.trim().take(220)}
            .firstOrNull{it.length>=5 && !IndexedMediaQuality.navigationTitle(it)}
            .orEmpty()
    }

    fun entries(doc:Document,source:String,area:String,time:Long):List<IndexedItem> {
        if(!owns(source,area))return emptyList()
        val host=host(source)
        val selector=when(host){
            "jav.guru" ->
                // Real Jav Guru markup uses listing wrappers that are not
                // consistently article/post elements. Select anchors first,
                // then admit ONLY strict publisher watch URLs below.
                "a[href]"
            "cums.net" ->
                "a[href*='/video/'],.video-item a[href],.video-card a[href]"
            else ->
                "a[href*='/watch/'],article a[href],.video-item a[href],main a[href]"
        }
        val found=LinkedHashMap<String,IndexedItem>()
        for(link in doc.select(selector).take(5000)){
            val url=link.absUrl("href").substringBefore('#')
            if(!isListingPage(source,url))continue
            // Archivebate's real post-Continue catalog exposes verified
            // /watch/<numeric ID> anchors without article/card wrappers or
            // descriptive anchor text. Chrome on 2026-11-13 returned 36 of
            // these links, including /watch/16458050, and played one.
            // Keep an observed ID as the neutral label rather than dropping
            // all entries or inventing a scene/performer title.
            val name=title(link).ifBlank {
                if(host=="archivebate.com"){
                    val id=runCatching{URI(url).path.orEmpty().trimEnd('/')
                        .substringAfterLast('/')}.getOrDefault("")
                    if(id.matches(Regex("[0-9]{1,16}")))
                        "Archivebate recording #"+id else ""
                }else ""
            }
            if(name.isBlank())continue
            val parent=link.closest("article,.post,.video-item,.video-card,.video-block,.thumb,.item,.card")
            val image=link.selectFirst("img")?:parent?.selectFirst("img")
            val cover=image?.let{SitePreviewImage.best(it)}
                ?.takeIf{SearchRanking.validUrl(it)}
            val current=found[url]
            if(current==null || (current.preview.isNullOrBlank() && cover!=null)){
                found[url]=IndexedItem(url,name,cover,source,area,"video",time,false)
            }
            if(found.size>=500)break
        }
        return found.values.toList()
    }
}
