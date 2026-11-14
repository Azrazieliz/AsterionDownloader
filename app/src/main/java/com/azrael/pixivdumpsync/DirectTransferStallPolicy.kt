package com.azrael.pixivdumpsync

/** An OS-owned transfer is not allowed to occupy a concurrency slot forever
 * while receiving zero bytes. No data-bearing transfer is cancelled here.
 * Android statuses are passed as booleans to keep this policy testable.
 */
internal object DirectTransferStallPolicy {
    const val ZERO_BYTE_TIMEOUT_MS=150_000L
    const val TERMINAL_RETRY_ATTEMPTS=5

    fun stalled(pendingOrRunning:Boolean,bytes:Long,created:Long,now:Long):Boolean =
        pendingOrRunning && bytes==0L && created>0L &&
            now>=created && now-created>=ZERO_BYTE_TIMEOUT_MS
}
