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
    /** Initial DOM load gets time to render, but subsequent selected-player
     * polls must fit within BrowserPageFetch's actual 45-second deadline.
     * The prior 1.8s inspection + 1.4s retry consumed 67s over 21
     * JAVIDEO polls, exceeding the entire retrieval window.
     */
    fun inspectionDelayMs(selectedPage:Boolean,attempt:Int):Long =
        if(selectedPage && attempt>0)200L else 1_800L

    fun maxPolls(host:String):Int = when(host){
        "3dhentaix.com" -> 11  // ~35 s of browser/player startup at most
        // The publisher's genuine Play action has been observed taking ~27s
        // to attach the active iframe; eleven 1.4s polls stopped too early.
        // Remain inside BrowserPageFetch's existing 45s hard timeout.
        "javideo.net" -> 21
        "play.javideo.net" -> 15
        // WatchStreamUS selected JWPlayer may populate VK_PLAYBACK HLS
        // asynchronously; the previous default six polls returned an
        // empty shell before the selected source appeared.
        "player.watchstreamus.com","cdn.watchstreamus.com" -> 12
        "video.playerrecord.cfd","vidara.to" -> 11 // Attested recording frame initializes HTML5 playback asynchronously
        "nhplayer.com" -> 18  // Exact selected AJAX may initialize after 15s; within 45s WebView cap
        "voe.sx","kamehaus.net" -> 9
        else -> 6
    }
}
