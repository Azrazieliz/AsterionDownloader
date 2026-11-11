package com.azrael.pixivdumpsync

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

class SyncJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        if (
            params == null ||
            !AppPower.enabled(this) ||
            !SessionStore.isLoggedIn(this) ||
            !SessionStore.autoSync(this) ||
            !SessionStore.followingFeedEnabled(this)
        ) {
            return false
        }

        if (SyncControl.snapshot().running) {
            return false
        }

        executor.execute {
            try {
                SessionStore.setLastAutoSyncResult(
                    applicationContext,
                    "Running"
                )
                val stats = SyncEngine(applicationContext).run(
                    mode = SyncMode.LIVE,
                    selectedOnly = false,
                    feedOnly = true,
                    queueLiveIfBusy = false
                )
                val result = if (stats.errors > 0) {
                    "Finished with ${stats.errors} error(s)"
                } else {
                    "OK • ${stats.worksCompleted} downloaded • ${stats.skippedDone} already done"
                }
                SessionStore.setLastAutoSyncResult(applicationContext, result)
                jobFinished(params, stats.errors > 0)
            } catch (t: Throwable) {
                SessionStore.setLastAutoSyncResult(
                    applicationContext,
                    "Failed • ${t.message ?: t.javaClass.simpleName}"
                )
                jobFinished(params, true)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        SyncControl.stop()
        SessionStore.setLastAutoSyncResult(
            applicationContext,
            "Interrupted by Android • retry scheduled"
        )
        return true
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

}
