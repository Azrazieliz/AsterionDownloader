package com.azrael.pixivdumpsync

/**
 * A global active-row check starves every other module. Queue recovery is
 * module-scoped and never reclaims a source that already has active work.
 * The caller still rate-limits scheduler launches and obeys AppPower.
 */
internal object DownloadQueueRecoveryPolicy {
    fun eligibleSources(queued:Set<String>,active:Set<String>):Set<String> =
        queued - active
}
