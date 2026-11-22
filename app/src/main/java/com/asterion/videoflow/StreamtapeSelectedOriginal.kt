package com.asterion.videoflow

import android.content.Context
import com.azrael.pixivdumpsync.BrowserPageFetch
import org.jsoup.Jsoup
import java.net.URI

/**
 * Exact selected JAVIDEO STAPE#01 -> unique Streamtape iframe ->
 * player-published token-bearing GET /get_video?id=<same-frame-id>.
 * The site's main muted HTML5 advertisement and Play iframe are never
 * interpreted as the selected movie. The returned URL is an original-transfer
 * CANDIDATE, not a successful download receipt.
 */
internal object StreamtapeSelectedOriginal {
    private val hosts=setOf("streamtape.com","streamtape.to",
        "streamtape.net","streamtape.site","sbtape.com")
    private val video=Regex("""^/(?:e|v|embed)/([A-Za-z0-9_-]{5,100})/?$""")
    private val keys=setOf("id","expires","ip","token","stream","dl")
    private fun uri(raw:String):URI?=runCatching{URI(raw)}.getOrNull()
    private fun values(raw:String):Map<String,String>? {
        val pairs=raw.split('&').filter{it.isNotBlank()}
        if(pairs.isEmpty() || pairs.size>9)return null
        val map=linkedMapOf<String,String>()
        for(pair in pairs){
            val split=pair.split('=',limit=2)
            if(split.size!=2 || split[0] !in keys ||
                split[1].length>200 || map.containsKey(split[0]))return null
            map[split[0]]=split[1]
        }
        return map
    }
    fun selectedFrame(article:String,frame:String):String? {
        val owner=uri(article)?:return null
        val player=uri(frame)?:return null
        if(owner.scheme!="https" || owner.host?.lowercase()!="javideo.net" ||
            !VideoSiteProfiles.isVideoPage(article) ||
            player.scheme!="https" || player.host?.lowercase() !in hosts ||
            player.port!=-1 || player.userInfo!=null ||
            player.rawQuery!=null || player.rawFragment!=null)return null
        return video.matchEntire(player.path.orEmpty())?.groupValues?.get(1)
    }
    fun isSelectedDirect(frame:String,link:String):Boolean {
        val expected=video.matchEntire(uri(frame)?.path.orEmpty())
            ?.groupValues?.get(1)?:return false
        val original=uri(frame)?:return false
        val target=uri(link)?:return false
        if(original.scheme!="https" || original.host?.lowercase() !in hosts ||
            target.scheme!="https" || target.host?.lowercase() !in hosts ||
            target.port!=-1 || target.userInfo!=null ||
            target.rawFragment!=null || target.path!="/get_video")return false
        val parameters=values(target.rawQuery.orEmpty())?:return false
        if(parameters["id"]!=expected)return false
        return parameters["token"]?.matches(Regex("""[A-Za-z0-9_-]{4,160}"""))==true
    }
    fun isSignedDirect(link:String):Boolean {
        val u=uri(link)?:return false
        if(u.scheme!="https" || u.host?.lowercase() !in hosts ||
            u.port!=-1 || u.userInfo!=null || u.rawFragment!=null ||
            u.path!="/get_video")return false
        val params=values(u.rawQuery.orEmpty())?:return false
        return params["id"]?.matches(Regex("""[A-Za-z0-9_-]{5,100}"""))==true &&
            params["token"]?.matches(Regex("""[A-Za-z0-9_-]{4,160}"""))==true
    }
    fun isSelectedArticleMedia(article:String,link:String):Boolean =
        uri(article)?.let{it.scheme=="https" &&
            it.host?.lowercase()=="javideo.net" &&
            VideoSiteProfiles.isVideoPage(article)}==true &&
        isSignedDirect(link)

    fun resolve(context:Context,article:String,frame:String):String? {
        if(selectedFrame(article,frame)==null)return null
        val html=BrowserPageFetch.html(context,frame,playerReferer=article)
        val doc=Jsoup.parse(html,frame)
        val selected=doc.select("meta[name=asterion-selected-streamtape-direct][content]")
            .map{it.attr("content")}.distinct().singleOrNull()?:return null
        return selected.takeIf{isSelectedDirect(frame,it)}
    }
}
