package com.asterion.videoflow

/** Manual videos are individually requested independent downloads.
 * Episode ordering only constrains automatic watched-site backfill,
 * never two videos the user explicitly queued for parallel transfer.
 */
internal object VideoQueueClaimPolicy {
    fun enforceEpisodeOrder(watchId:Long):Boolean=watchId>0L
}
