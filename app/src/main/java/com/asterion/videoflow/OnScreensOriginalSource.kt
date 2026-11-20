package com.asterion.videoflow

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/** An actual OnScreens recording has exactly one publisher-labelled external
 * player, distinct from banner iframes, related recordings and Nitroflare
 * paywalled file-host links. The selected player is not yet an MP4 receipt.
 */
internal object OnScreensOriginalSource {
    fun isSelectedRecording(article:String):Boolean {
        val u=runCatching{URI(article)}.getOrNull()?:return false
        return u.scheme=="https" &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")=="onscreens.me" &&
            VideoSiteProfiles.isVideoPage(article)
    }

    fun selectedPlayer(article:String,source:String):Boolean {
        if(!isSelectedRecording(article))return false
        val u=runCatching{URI(source)}.getOrNull()?:return false
        if(u.scheme!="https" || u.userInfo!=null || u.port!=-1 ||
            u.rawFragment!=null)return false
        val host=u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        return host in setOf("voe.sx","doodstream.com","dood.to","dood.watch") &&
            Regex("""^/(?:e|d)/[A-Za-z0-9_-]{5,100}/?$""")
                .matches(u.path.orEmpty())
    }

    fun published(article:String,html:String):String? {
        if(!isSelectedRecording(article))return null
        val page=Jsoup.parse(html,article)
        val selected=page.select("a[href]").asSequence().filter { anchor->
            anchor.closest("aside,.related,.recommended,.recommendations,"+
                ".ads,.advertisement,.banner,.sidebar,[data-ad]")==null &&
                listOf(anchor.text(),anchor.attr("title"),
                    anchor.attr("aria-label"),anchor.selectFirst("img[alt]")
                        ?.attr("alt").orEmpty()).any { label->
                    label.trim().equals("Open the video player",true)
                }
        }.mapNotNull{it.absUrl("href").takeIf{url->selectedPlayer(article,url)}}
            .distinct().toList()
        return selected.singleOrNull()
    }
}
