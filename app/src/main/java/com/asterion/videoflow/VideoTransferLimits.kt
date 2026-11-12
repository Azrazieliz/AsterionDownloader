package com.asterion.videoflow

/** Hard upper bounds for embedded yt-dlp operations. Actual transfers may run
 * longer than preflights while making measurable progress.
 */
internal object VideoTransferLimits {
    const val POLL_MS = 2_000L
    const val PREFLIGHT_MS = 50_000L
    const val TRANSFER_IDLE_MS = 110_000L
    const val TRANSFER_MAX_MS = 48L * 60L * 60_000L

    fun expired(stage:String,elapsedMs:Long,idleMs:Long):String?=when {
        stage=="metadata" && elapsedMs>=PREFLIGHT_MS ->
            "Metadata inspection timed out after " + (PREFLIGHT_MS/1000) + "s"
        stage=="transfer" && elapsedMs>=TRANSFER_MAX_MS ->
            "Original transfer reached its safety time limit"
        stage=="transfer" && idleMs>=TRANSFER_IDLE_MS ->
            "Original transfer made no measurable progress for " +
                (TRANSFER_IDLE_MS/1000) + "s"
        else->null
    }
}
