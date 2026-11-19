package com.asterion.videoflow

import java.util.Locale

/** Preserve the useful underlying cause of Future.get/Chaquopy failures.
 * Never persist signed URLs, authentication headers or session tokens.
 */
internal object VideoErrorDetail {
    private val url=Regex("""(?i)https?://[^\s'"<>]+""")
    private val secret=Regex(
        """(?i)(?:cookie|authorization|bearer|token|password|signature|apikey|api_key)\s*[:=]\s*[^\s;,]+""")
    private fun safe(raw:String):String {
        return raw.replace(url,"[media URL redacted]")
            .replace(secret,"[credential redacted]")
            .replace(Regex("[\\r\\n\\t]+")," ")
            .trim().take(320)
    }
    /** Future.get() and Chaquopy may wrap InterruptedException, whose
     * interrupt flag is usually CLEARED when thrown. Treat a real
     * cancellation as scheduler interruption rather than exhausting the
     * selected original's site-retry budget or discarding its partial bytes.
     * Exact runtime class text covers JNI wrappers that cannot preserve an
     * actual java.lang.InterruptedException instance.
     */
    fun workerInterrupted(t:Throwable):Boolean {
        val chain=generateSequence(t){it.cause}.take(12).toList()
        return chain.any{it is InterruptedException ||
            it is java.util.concurrent.CancellationException} ||
            chain.any{
                it.javaClass.simpleName=="InterruptedException" ||
                it.message?.trim() in setOf(
                    "InterruptedException","java.lang.InterruptedException")
            }
    }
    fun summary(throwable:Throwable):String {
        val chain=generateSequence(throwable){it.cause}
            .take(9).toList()
        val root=chain.lastOrNull{!it.message.isNullOrBlank()}
            ?:chain.lastOrNull()?:throwable
        val raw=(root.message?:root.javaClass.simpleName).trim()
        val lower=raw.lowercase(Locale.ROOT)
        val clear=when {
            // A public publisher host resolving to 127.0.0.1 cannot reach its
            // genuine HTTPS server. This is a phone DNS/blocker/route issue,
            // not Python unsupported URL or an entitlement to fabricate media.
            Regex("""(?i)(?:failed to connect to|connectexception).{0,180}/(?:127\.0\.0\.1|0\.0\.0\.0):443""")
                .containsMatchIn(raw)->
                "Selected publisher host is blocked by this Android network route "+
                "(resolved to local loopback). Check DNS/site filtering or an "+
                "authorized per-site route before retrying; zero original bytes"
            Regex("""\b(401|403)\b|forbidden|access denied|sign.?in|authentication required""")
                .containsMatchIn(lower)->
                "Provider denied playback or transfer (HTTP 401/403); verify direct browser access and connected account"
            Regex("""\b429\b|too many requests|rate.?limit""")
                .containsMatchIn(lower)->
                "Provider rate-limited requests (HTTP 429); wait for cooldown: "+raw
            Regex("""\b404\b|\b410\b|video removed|original not found""")
                .containsMatchIn(lower)->
                "Original unavailable (HTTP 404/410); verify the selected page: "+raw
            Regex("""drm protected|widevine|protected playback""")
                .containsMatchIn(lower)->
                "Publisher uses DRM-protected playback; unsupported original: "+raw
            Regex("""module.?not.?found|no module named|python.*not.*start|importerror""")
                .containsMatchIn(lower)->
                "Embedded Python dependency failed: "+raw
            Regex("""read.only file system|erofs|permission denied|eacces""")
                .containsMatchIn(lower)->
                "Video staging is not writable: "+raw
            Regex("""unsupported url|no suitable extractor|unable to extract|no video formats""")
                .containsMatchIn(lower)->
                "Video extractor cannot resolve this publisher's current player (extractor repair required): "+raw
            else->raw
        }
        val python=chain.any{
            it.javaClass.name.contains("PyException") ||
                it.javaClass.name.contains("chaquo",true)
        }
        return (if(python)"Python extractor: " else "Video transfer: ")+safe(clear)
    }
}
