package com.azrael.pixivdumpsync

/**
 * Rendered original-player detection budgets. The selected 3DHentai
 * article explicitly populates video.src only after an authorized signed
 * playback URL is requested by its own JavaScript. A 6-poll budget often
 * returns the empty placeholder first. Bound retries to the existing
 * 45-second browser timeout rather than accepting an advertisement.
 */
internal object SelectedPlayerWaitPolicy {
    fun maxPolls(host:String):Int = when(host){
        "3dhentaix.com" -> 11  // ~35 s of browser/player startup at most
        "javideo.net","voe.sx","kamehaus.net" -> 9
        else -> 6
    }
}
