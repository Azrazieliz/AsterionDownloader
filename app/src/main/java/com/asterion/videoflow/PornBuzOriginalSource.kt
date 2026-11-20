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
