package com.asterion.videoflow

import java.net.URI
import java.util.Locale

/** Fail closed for very short, UNCORROBORATED selected videos from the
 * publishers where the owner reported 20-second previews saved as full.
 * This deliberately leaves confirmed working Jav Guru, Pornhub, Rule34
 * and Eporner original paths completely unchanged.
 *
 * An actual publisher/independent full-runtime reference takes precedence.
 * Valid intentional short recordings with a corroborated runtime pass.
 */
internal object UnverifiedSelectedPreviewGuard {
    private val affected=setOf(
        "taiav.com","javideo.net","livecamrips.to",
        "uncensoredhentai.xxx",
        "avple.tv","pimpbunny.com","xchina.co","bubblecum.lol",
        "pornavhd.com","camstreams.tv"
    )
    private const val UNVERIFIED_PREVIEW_SECONDS=60L
    fun shouldHold(selectedUrl:String,actualDurationMs:Long,
                   knownOriginalSeconds:Long?):Boolean {
        val host=runCatching{
            URI(selectedUrl).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.").removePrefix("en.")
        }.getOrDefault("")
        if(host !in affected)return false
        if(knownOriginalSeconds!=null && knownOriginalSeconds>0L)
            return false // Main full-length validator checks this reference.
        return actualDurationMs in 1 until UNVERIFIED_PREVIEW_SECONDS*1000L
    }
}
