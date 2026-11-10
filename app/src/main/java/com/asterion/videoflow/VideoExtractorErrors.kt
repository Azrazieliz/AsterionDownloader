package com.asterion.videoflow

/** Extract only a safe error category from yt-dlp stdout. Original extractor
 * text may contain signed media URLs or cookies; it is never copied into
 * the download database or diagnostics.
 */
internal object VideoExtractorErrors {
    fun message(output:String,exitCode:Int,operation:String):String {
        val category=VideoFailurePolicy.classify(output)
        val prefix=if(operation=="metadata")"Video metadata" else "Video transfer"
        return when(category){
            VideoFailurePolicy.Kind.AUTH->
                prefix+" rejected by provider (HTTP 401/403 or authorization required)"
            VideoFailurePolicy.Kind.RATE_LIMIT->
                prefix+" rate-limited (HTTP 429)"
            VideoFailurePolicy.Kind.GONE->
                prefix+" unavailable (HTTP 404/410)"
            VideoFailurePolicy.Kind.UNSUPPORTED->
                prefix+" unsupported by embedded extractor"
            VideoFailurePolicy.Kind.TRANSIENT->
                prefix+" network/host error (timeout or HTTP 5xx)"
            VideoFailurePolicy.Kind.METADATA->
                prefix+" returned no usable metadata"
            VideoFailurePolicy.Kind.OTHER->
                prefix+" extractor failed (exit "+exitCode+")"
        }
    }
}
