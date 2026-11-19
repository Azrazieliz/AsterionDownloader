package com.azrael.pixivdumpsync

/**
 * Rendered original-player detection budgets. The selected 3DHentai
 * article explicitly populates video.src only after an authorized signed
 * playback URL is requested by its own JavaScript. A 6-poll budget often
 * returns the empty placeholder first. Bound retries to the existing
 * 45-second browser timeout rather than accepting an advertisement.
 */
internal object SelectedPlayerWaitPolicy {
    /** The selected NHPlayer /player.php can render unrelated <video>
     * placeholders before its first-party AJAX sets window.videoReady.
     * Only the selected /v/ -> /player.php -> signed CDN source may
     * complete this browser poll; neither an ad nor empty MP4 counts.
     */
    fun selectedNhPlayerReady(iframe:String,server:String,html:String):Boolean {
        val media=org.jsoup.Jsoup.parse(html,server)
            .select("video[src],video source[src]")
            .map{it.attr("src")}
            .filter{
                com.asterion.videoflow.SiteSpecificOriginalSources
                    .selectedNhPlayerAjaxOriginal(iframe,server,it)
            }.distinct()
        return media.size==1
    }
    fun maxPolls(host:String):Int = when(host){
        "3dhentaix.com" -> 11  // ~35 s of browser/player startup at most
        "javideo.net" -> 11 // Active Play iframe may attach late
        "voe.sx","kamehaus.net","nhplayer.com" -> 9
        else -> 6
    }
}
