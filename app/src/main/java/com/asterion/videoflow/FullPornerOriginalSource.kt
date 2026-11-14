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
        return Regex("""^/vid/[A-Za-z0-9_-]{6,250}/?$""")
            .matches(u.path.orEmpty()) &&
            !FullLengthVideoPolicy.isExplicitPreview("",raw)
    }
    /** Native transport rechecks real video/mp4 and MP4 ftyp bytes. */
    fun verifiedMediaHost(raw:String):Boolean =
        safeHost(raw,"xiaoshenke.net") &&
            Regex("""^/vid/[A-Za-z0-9_-]{6,250}/?$""")
                .matches(uri(raw)?.path.orEmpty())
}
