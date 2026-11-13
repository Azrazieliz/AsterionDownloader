package com.asterion.videoflow

import java.util.Locale

/** Permanent authorization/unsupported cases must not be retried indefinitely. */
internal object VideoFailurePolicy {
    enum class Kind { AUTH, RATE_LIMIT, GONE, UNSUPPORTED, METADATA, TRANSIENT, OTHER }
    fun classify(reason:String):Kind {
        val text=reason.lowercase(Locale.ROOT)
        return when {
            Regex("""(?:\b401\b|\b403\b|forbidden|unauthorized|login required|sign in|authentication required|access denied|captcha|verify you are human|browser verification required|website verification page|short preview clips)""").containsMatchIn(text)->Kind.AUTH
            Regex("""(?:\b429\b|too many requests|rate limit|ratelimit)""").containsMatchIn(text)->Kind.RATE_LIMIT
            Regex("""(?:\b404\b|\b410\b|not found|gone|removed video|video unavailable)""").containsMatchIn(text)->Kind.GONE
            Regex("""(?:unsupported url|no suitable extractor|unsupported site|extractor not supported|drm protected|unable to extract hash|unable to extract short video id|no video formats found|player metadata unavailable|extractor repair required)""").containsMatchIn(text)->Kind.UNSUPPORTED
            Regex("""(?:no valid metadata json|no metadata json|cannot inspect complete-video metadata)""").containsMatchIn(text)->Kind.METADATA
            Regex("""(?:timeout|timed out|connection|reset by peer|broken pipe|network unreachable|\b502\b|\b503\b|\b504\b|temporary|gateway error)""").containsMatchIn(text)->Kind.TRANSIENT
            else->Kind.OTHER
        }
    }
    fun actionable(kind:Kind)=kind in setOf(Kind.AUTH,Kind.GONE,Kind.UNSUPPORTED)
    fun maxRetries(kind:Kind):Int=if(kind==Kind.METADATA)2 else 6
    fun delayMs(kind:Kind,attempt:Int):Long {
        if(kind==Kind.RATE_LIMIT)return 10L*60_000L
        val shift=(attempt-1).coerceIn(0,5)
        return (20_000L shl shift).coerceAtMost(15L*60_000L)
    }
}
