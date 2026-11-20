package com.asterion.videoflow

/**
 * Attempt-wide source-connection watchdog. Previously the presence of OLD
 * resumable .part bytes disabled the global first-byte deadline, allowing
 * many selected publisher candidates to cycle indefinitely at "Connecting".
 *
 * Only NEW bytes verified during THIS attempt extend its connection budget.
 * Existing saved originals and resumable fragments are never deleted here.
 */
internal class VideoConnectionWatchdog(
    startedElapsedMs:Long,
    initialStagedBytes:Long
) {
    private var lastForwardProgressMs=startedElapsedMs
    private var greatestStagedBytes=initialStagedBytes.coerceAtLeast(0L)
    private var greatestNestedVerifiedBytes=0L

    /** Stable elapsedRealtime inputs; no wall-clock or network dependencies. */
    fun exhausted(nowElapsedMs:Long,stagedBytes:Long,
                  freshNestedVerifiedBytes:Long=0L):Boolean {
        val current=stagedBytes.coerceAtLeast(0L)
        val nested=freshNestedVerifiedBytes.coerceAtLeast(0L)
        if(current>greatestStagedBytes || nested>greatestNestedVerifiedBytes){
            greatestStagedBytes=maxOf(greatestStagedBytes,current)
            greatestNestedVerifiedBytes=maxOf(greatestNestedVerifiedBytes,nested)
            lastForwardProgressMs=nowElapsedMs
        }
        return nowElapsedMs>=lastForwardProgressMs &&
            nowElapsedMs-lastForwardProgressMs>=
                VideoTransferLimits.TOTAL_FIRST_BYTE_BUDGET_MS
    }
}
