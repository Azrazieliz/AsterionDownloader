package com.asterion.videoflow

import org.jsoup.Jsoup
import java.net.URI
import java.util.Locale

/**
 * Actual 2026 publisher layout: /videos/<numeric-id>/<selected-slug> uses
 * kt_player with encoded function/... source metadata, NOT a static HTML5
 * video or player iframe. Let its own public browser player produce currentSrc;
 * do not invent a KVS decode key or treat a video card as original media.
 */
internal object OnlyHentaiKvsOriginalSource {
    private val path=Regex(
        """^/videos/([0-9]{1,12})/[A-Za-z0-9][A-Za-z0-9_-]{2,250}/?$""")
    private val videoId=Regex(
        """(?i)(?:['"]?video_id['"]?\s*:\s*|\bvideo_id\s*=\s*)['"]?([0-9]{1,12})""")
    fun selectedId(selected:String):String? {
        val u=runCatching{URI(selected)}.getOrNull()?:return null
        val host=u.host.orEmpty().lowercase(Locale.ROOT)
        if(u.scheme!="https" || u.userInfo!=null || u.port!=-1 ||
            host !in setOf("onlyhentaistuff.com","www.onlyhentaistuff.com") ||
            u.rawFragment!=null || !u.rawQuery.isNullOrBlank())return null
        return path.matchEntire(u.path.orEmpty())?.groupValues?.get(1)
    }
    fun hasSelectedConfig(selected:String,html:String):Boolean {
        val id=selectedId(selected)?:return false
        if(html.length !in 100..2_000_000 || !html.contains("kt_player"))return false
        val doc=Jsoup.parse(html,selected)
        val scripts=doc.select("script:not([src])").take(60).map{
            it.data().ifBlank{it.html()}
        }.filter{it.contains("kt_player") && it.contains("video_id")}
        if(scripts.size!=1)return false
        // Real public KVS pages use a player-internal video_id that is
        // NOT the numerical segment in the visible /videos/<id>/<slug>.
        // The selected article URL and unique #kt_player container bind
        // provenance; comparing these unrelated IDs rejects real originals.
        val players=doc.select("#kt_player")
        val script=scripts.single()
        return players.size==1 &&
            Regex("""\bkt_player\s*\(""").containsMatchIn(script) &&
            script.contains("flashvars") && script.contains("video_url") &&
            videoId.containsMatchIn(script) && id.length in 1..12
    }
    /** Only a publisher-rendered #kt_player video matching the selected ID. */
    fun rendered(selected:String,html:String):String? {
        val id=selectedId(selected)?:return null
        val doc=Jsoup.parse(html,selected)
        val root=doc.select("#kt_player[data-selected-page-id]").singleOrNull()
            ?:return null
        if(root.attr("data-selected-page-id")!=id)return null
        if(root.closest("aside,.related,.recommended,.recommendations,"+
                ".ads,.advertisement,.sidebar,[data-ad]")!=null)return null
        val videos=root.select("video[src],video source[src]")
        val links=LinkedHashSet<String>()
        for(video in videos.take(6)){
            val source=video.attr("src")
            if(source.isBlank())continue
            val url=runCatching{URI(source)}.getOrNull()?:continue
            if(url.scheme!="https" || url.host.isNullOrBlank() ||
                url.userInfo!=null || url.port!=-1 || url.rawFragment!=null ||
                FullLengthVideoPolicy.isExplicitPreview("",source))continue
            val media=url.path.orEmpty()
            if(!Regex("""(?i)\.(?:mp4|webm|m4v|m3u8|mpd)$""")
                    .containsMatchIn(media))continue
            links+=url.toASCIIString()
        }
        return links.singleOrNull()
    }
}
