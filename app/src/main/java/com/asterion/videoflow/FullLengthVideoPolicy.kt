package com.asterion.videoflow

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Acquisition-only guard: never mistake a short/high-resolution preview for a full work.
 * If no trusted full-scene runtime can be established, automatic retrieval is held.
 * This deliberately does not change Rounder's cataloging or Core's deduplication.
 */
object FullLengthVideoPolicy {
    enum class Verdict { ACCEPT, HOLD_FOR_REFERENCE, REJECT_SHORT_VERSION }
    data class Decision(val verdict:Verdict,val reason:String)
    private val previewWords=Regex(
        """(?i)(?:^|[^\p{L}\p{N}])(?:trailer|teaser|preview|sample|promo|promotional|shortened|short\s*ver(?:sion)?|highlights?|excerpt|abridged|cutdown|sneak\s*peek|free\s*clip|part\s*\d+|scene\s*\d+\s*of\s*\d+)(?:$|[^\p{L}\p{N}])""")
    private val preferredReferenceHosts=setOf("watchporn.to","freepornvideos.xxx")
    fun knownFullReferenceHost(url:String):Boolean {
        val host=runCatching{URI(url).host.lowercase(Locale.ROOT).removePrefix("www.")}
            .getOrDefault("")
        return host in preferredReferenceHosts
    }
    /** Preserve substantive scene titles, performer identities and release IDs.
     * Never merge unrelated files merely because they share a generic keyword.
     */
    fun sceneKey(value:String):String {
        val clean=Normalizer.normalize(value,Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("""(?i)\b(?:full\s*scene|full\s*video|complete|1080p|2160p|4k|hd|fhd|uhd)\b""")," ")
            .replace(Regex("""[^\p{L}\p{N}]+""")," ")
            .trim().replace(Regex("\\s+")," ")
        return clean.take(220)
    }
    fun assess(pageTitle:String,extractedTitle:String,url:String,durationSec:Long?,
               referenceSec:Long?):Decision {
        if(previewWords.containsMatchIn(pageTitle)||
            previewWords.containsMatchIn(extractedTitle)||
            previewWords.containsMatchIn(runCatching{URI(url).path}.getOrDefault("")))
            return Decision(Verdict.REJECT_SHORT_VERSION,"Trailer, sample or segmented cut")
        val duration=durationSec?.takeIf{it>0}
            ?:return Decision(Verdict.HOLD_FOR_REFERENCE,"Extractor did not report duration")
        if(duration<60L)
            return Decision(Verdict.REJECT_SHORT_VERSION,"Video shorter than one minute")
        if(referenceSec!=null && referenceSec>0){
            // Allow only a few seconds of encoder/container timing variance.
            // A 50-minute original cut down to 49 minutes is NOT acceptable.
            if(duration+5L<referenceSec)
                return Decision(Verdict.REJECT_SHORT_VERSION,
                    "Only ${duration}s of expected ${referenceSec}s")
            return Decision(Verdict.ACCEPT,"Verified against full-version runtime")
        }
        // A site, popularity/reviews, a "full" label, and a 20/50/90-minute
        // runtime are NOT proof that a release is complete. This includes
        // preferred archives and short-cut-heavy providers such as Porngo.
        // Without an independently established original runtime, fail closed:
        // do not download an unverified cut, regardless of provider or length.
        return Decision(Verdict.HOLD_FOR_REFERENCE,
            "Full original runtime not independently verified; automatic transfer held")
    }
}
