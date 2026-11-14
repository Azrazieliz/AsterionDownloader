package com.asterion.videoflow

import java.net.URI
import java.util.Locale

/** FullPorner selected /watch/<24-hex> creates an official
 * /player/<id>/<layout> whose original MP4s are served by
 * xiaoshenke.net/vid/<opaque-id> WITHOUT a .mp4 extension.
 *
 * Live first-party proof (2026-11-14): HTTP 206, video/mp4,
 * Content-Range 0-255/1231039363, MP4 ftyp. Never authorize
 * an arbitrary third-party URL or unrelated page/recommendation.
 */
internal object FullPornerOriginalSource {
    private fun uri(raw:String):URI?=runCatching{URI(raw)}.getOrNull()
    private fun safeHost(raw:String,host:String):Boolean {
        val u=uri(raw)?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")==host
    }
    fun selectedPage(raw:String):Boolean =
        safeHost(raw,"fullporner.com") &&
            Regex("""^/watch/[a-fA-F0-9]{24}/?$""")
                .matches(uri(raw)?.path.orEmpty())
    fun selectedPlayer(raw:String):Boolean =
        safeHost(raw,"fullporner.com") &&
            Regex("""^/player/[0-9]{1,12}/[0-9]{1,4}/?$""")
                .matches(uri(raw)?.path.orEmpty())
    fun verifiedMedia(selected:String,player:String,raw:String):Boolean {
        if(!selectedPage(selected) || !selectedPlayer(player) ||
            !safeHost(raw,"xiaoshenke.net"))return false
        val u=uri(raw)?:return false
        return Regex("""^/vid/[A-Za-z0-9_-]{6,250}(?:/(?:360|480|720|1080))?/?$""")
            .matches(u.path.orEmpty()) &&
            !FullLengthVideoPolicy.isExplicitPreview("",raw)
    }
    /**
     * Selected FullPorner /player/<id>/<qualityMask> renders its MP4 URLs
     * from a publisher-owned inline script. The ID is explicitly reversed,
     * and bit flags 1/2/4/8 publish 360/480/720/1080p respectively.
     * Require the script literals to match this exact selected iframe ID,
     * so a recommended clip or arbitrary URL cannot be substituted.
     */
    fun publishedQualities(selected:String,player:String,html:String):List<String>{
        if(!selectedPage(selected) || !selectedPlayer(player))return emptyList()
        val route=Regex("""^/player/([0-9]{1,12})/([0-9]{1,4})/?$""")
            .matchEntire(uri(player)?.path.orEmpty())?:return emptyList()
        val scripts=org.jsoup.Jsoup.parse(html,player)
            .select("script:not([src])").take(35)
        val script=scripts.firstOrNull { node ->
            val body=node.data().ifBlank{node.html()}
            body.contains("xiaoshenke.net") &&
                body.contains("/vid") && body.contains("btq(quality)") &&
                body.contains("v.media")
        }?.let{it.data().ifBlank{it.html()}}?:return emptyList()
        val id=Regex("""(?i)\bvar\s+id\s*=\s*["']([0-9]{1,12})["']\s*\.split\(['"]{2}\)\s*\.reverse\(\)\s*\.join\(['"]{2}\)""")
            .find(script)?.groupValues?.get(1)?:return emptyList()
        val mask=Regex("""(?i)\bvar\s+quality\s*=\s*parseInt\(["']([0-9]{1,4})["']\)""")
            .find(script)?.groupValues?.get(1)?.toIntOrNull()?:return emptyList()
        if(id!=route.groupValues[1] || mask.toString()!=route.groupValues[2] ||
            mask !in 1..15)return emptyList()
        val actualId=id.reversed()
        return listOf(1080 to 8,720 to 4,480 to 2,360 to 1)
            .filter{(_,flag)->mask and flag!=0}
            .map{(quality,_)->"https://xiaoshenke.net/vid/$actualId/$quality"}
            .filter{verifiedMedia(selected,player,it)}
    }

    /** Native transport rechecks real video/mp4 and MP4 ftyp bytes. */
    fun verifiedMediaHost(raw:String):Boolean =
        safeHost(raw,"xiaoshenke.net") &&
            Regex("""^/vid/[A-Za-z0-9_-]{6,250}(?:/(?:360|480|720|1080))?/?$""")
                .matches(uri(raw)?.path.orEmpty())
}
