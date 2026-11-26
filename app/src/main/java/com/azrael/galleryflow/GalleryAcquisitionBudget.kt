package com.azrael.galleryflow

/** Bounds metadata discovery only. Full original transfers are never limited
 * by this clock; their existing stream integrity and idle limits still apply. */
internal class GalleryAcquisitionBudget(
    private val clock:()->Long=System::nanoTime,
    private val durationMs:Long=120_000L,
    private val cancelled:()->Boolean={ !SyncControl.checkpoint() }
) {
    private val started=clock()
    fun nextTimeoutMs():Int {
        if(cancelled())throw SyncCancelledException()
        val elapsed=(clock()-started).coerceAtLeast(0L)/1_000_000L
        val remaining=durationMs-elapsed
        if(remaining<=0L)throw java.net.SocketTimeoutException("Archive metadata acquisition exceeded its deadline")
        return minOf(30_000L,remaining).toInt()
    }
}
