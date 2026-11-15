package com.asterion.videoflow

import java.util.Locale

/** Permanent authorization/unsupported cases must not be retried indefinitely. */
internal object VideoFailurePolicy {
    enum class Kind { AUTH, RATE_LIMIT, GONE, UNSUPPORTED, METADATA, DNS, TRANSIENT, OTHER }
    fun classify(reason:String):Kind {
        val text=reason.lowercase(Locale.ROOT)
        return when {
            Regex("""(?:no address associated with hostname|unknownhostexception|name or service not known|dns lookup failed|unable to resolve host|temporary failure in name resolution)""")
                .containsMatchIn(text)->Kind.DNS
            Regex("""(?:\b401\b|\b403\b|forbidden|unauthorized|login required|sign in|authentication required|access denied|captcha|verify you are human|browser verification required|website verification page|site verification|authorized browser session|short preview clips|still exits in france|requires a real remote exit|active android vpn required|selected public exit country could not be verified)""").containsMatchIn(text)->Kind.AUTH
            Regex("""(?:\b429\b|too many requests|rate limit|ratelimit)""").containsMatchIn(text)->Kind.RATE_LIMIT
            Regex("""(?:\b404\b|\b410\b|not found|gone|removed video|video unavailable)""").containsMatchIn(text)->Kind.GONE
            Regex("""(?:unsupported url|no suitable extractor|unsupported site|extractor not supported|drm protected|unable to extract hash|unable to extract short video id|no video formats found|player metadata unavailable|extractor repair required|video extractor cannot resolve|no publisher original or supported player found|no verified original found on this page|selected publisher did not expose a transferable original|selected video yielded no original bytes after|did not expose a selected stream player|selected stream players failed|selected recording has no accessible player video source|selected file hosts exposed no verified media url|file-host redirect loop|multiple unverified mp4 downloads|ambiguous media files|original identity unverified|unrelated media and generic extractor fallback are prohibited)""").containsMatchIn(text)->Kind.UNSUPPORTED
            Regex("""(?:no valid metadata json|no metadata json|cannot inspect complete-video metadata)""").containsMatchIn(text)->Kind.METADATA
            Regex("""(?:timeout|timed out|connection|reset by peer|broken pipe|network unreachable|\b502\b|\b503\b|\b504\b|temporary|gateway error)""").containsMatchIn(text)->Kind.TRANSIENT
            else->Kind.OTHER
        }
    }
    fun actionable(kind:Kind)=kind in setOf(Kind.AUTH,Kind.GONE,Kind.UNSUPPORTED)
    fun maxRetries(kind:Kind):Int=when(kind){
        Kind.AUTH,Kind.GONE,Kind.UNSUPPORTED->0
        Kind.RATE_LIMIT,Kind.DNS->1
        Kind.METADATA->2
        Kind.TRANSIENT,Kind.OTHER->2
    }
    fun delayMs(kind:Kind,attempt:Int):Long {
        if(kind==Kind.RATE_LIMIT)return 10L*60_000L
        if(kind==Kind.DNS)return 2L*60_000L
        val shift=(attempt-1).coerceIn(0,5)
        return (20_000L shl shift).coerceAtMost(15L*60_000L)
    }
}
