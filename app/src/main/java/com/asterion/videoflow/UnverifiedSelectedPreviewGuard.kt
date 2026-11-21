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
        "pornavhd.com","camstreams.tv","pornbuz.com","notfans.com"
    )
    private const val UNVERIFIED_PREVIEW_SECONDS=60L
    fun shouldHold(selectedUrl:String,actualDurationMs:Long,
                   knownOriginalSeconds:Long?):Boolean {
        val host=runCatching{
            URI(selectedUrl).host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.").removePrefix("en.")
        }.getOrDefault("")
        if(host !in affected)return false
        val suspicious=actualDurationMs in 1 until UNVERIFIED_PREVIEW_SECONDS*1000L
        // PornBuz's Server 2 can itself expose a playable ~20s teaser.
        // An "expected" duration recorded from that very teaser is not
        // independent publisher proof: never let it validate itself as full.
        if(host=="pornbuz.com")return suspicious
        if(knownOriginalSeconds!=null && knownOriginalSeconds>0L)
            return false // Other providers retain their established policy.
        return suspicious
    }
}
