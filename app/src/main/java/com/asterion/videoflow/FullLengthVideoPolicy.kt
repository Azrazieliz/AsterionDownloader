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
        """(?i)(?:^|[^\p{L}\p{N}])(?:trailer|teaser|preview|sample|promo|promotional|shortened|short\s*ver(?:sion)?|highlights?|excerpt|abridged|cutdown|sneak\s*peek|free\s*clip)(?:$|[^\p{L}\p{N}])""")
    private val preferredReferenceHosts=setOf(
        "watchporn.to","freepornvideos.xxx","fullporner.com","archivebate.com",
        "xxxi.porn","helloporn.com","notfans.com","eporner.com"
    )
    fun knownFullReferenceHost(url:String):Boolean {
        val host=runCatching{URI(url).host.lowercase(Locale.ROOT).removePrefix("www.")}
            .getOrDefault("")
        return preferredReferenceHosts.any{host==it || host.endsWith("."+it)}
    }
    /** Preserve substantive scene titles, performer identities and release IDs.
     * Never merge unrelated files merely because they share a generic keyword.
     */
    fun sceneKey(value:String):String {
        val normalized=Normalizer.normalize(value,Normalizer.Form.NFKC)
        // Release identifiers survive translated titles, aliases, watermarks and
        // varying file names. A code must include a letter prefix and digits.
        val release=Regex("""(?i)(?:^|[^\p{L}\p{N}])([a-z]{2,10})[-_](\d{2,6})(?:$|[^\p{L}\p{N}])""")
            .find(normalized)
        if(release!=null)return "release:"+
            release.groupValues[1].lowercase(Locale.ROOT)+
            release.groupValues[2]
        val clean=normalized.lowercase(Locale.ROOT)
            .replace(Regex("""(?i)\b(?:full\s*scene|full\s*video|complete|1080p|2160p|4k|hd|fhd|uhd)\b""")," ")
            .replace(Regex("""[^\p{L}\p{N}]+""")," ")
            .trim().replace(Regex("\\s+")," ")
        return clean.take(220)
    }
    /** User-designated full-video providers are confidence sources; duration is
     * not used as an arbitrary threshold, even for very short originals.
     */
    fun isExplicitPreview(title:String,url:String):Boolean {
        val path=runCatching{URI(url).path.orEmpty()}.getOrDefault("")
        if(previewWords.containsMatchIn(title) || previewWords.containsMatchIn(path))
            return true
        if(runCatching{URI(url).host.orEmpty().endsWith("eporner.com")}.getOrDefault(false)){
            val excerpt=Regex("""(?i)(?:^|[^\p{L}\p{N}])(?:clips?|samples?|excerpts?|teasers?|trailers?)(?:$|[^\p{L}\p{N}])""")
            return excerpt.containsMatchIn(title) || excerpt.containsMatchIn(path)
        }
        return false
    }
    fun assess(pageTitle:String,extractedTitle:String,url:String,durationSec:Long?,
               referenceSec:Long?,publisherVerified:Boolean=false,
               userExplicitlyRequested:Boolean=false):Decision {
        if(isExplicitPreview(pageTitle,url) || isExplicitPreview(extractedTitle,url))
            return Decision(Verdict.REJECT_SHORT_VERSION,"Explicit preview or excerpt")
        if(knownFullReferenceHost(url))
            return Decision(Verdict.ACCEPT,"User-designated full-video provider")
        if(userExplicitlyRequested)
            return Decision(Verdict.ACCEPT,"Individually selected original")
        val observed=durationSec?.takeIf{it>0}
        if(referenceSec!=null && referenceSec>0 && observed!=null){
            if(observed+5L<referenceSec)
                return Decision(Verdict.REJECT_SHORT_VERSION,
                    "Observed runtime shorter than established reference")
            return Decision(Verdict.ACCEPT,
                if(publisherVerified)"Publisher-provided original" else "Reference matched")
        }
        return Decision(Verdict.HOLD_FOR_REFERENCE,
            "Automatic source lacks trusted original confidence")
    }
}
