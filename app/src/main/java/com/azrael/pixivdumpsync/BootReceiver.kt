package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * BOOT_COMPLETED and MY_PACKAGE_REPLACED have a strict broadcast deadline.
 * The old goAsync() receiver retained that deadline while initializing every
 * downloader and gallery scheduler. On a cold Android 15 emulator boot this
 * resulted in a real BOOT_COMPLETED ANR, killing the instrumentation process.
 * Queue a short durable restore job and return from the broadcast immediately.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            val request=JobInfo.Builder(
                RestoreDownloadSchedulesJob.JOB_ID,
                ComponentName(context,RestoreDownloadSchedulesJob::class.java)
            ).setMinimumLatency(20_000L)
                .setOverrideDeadline(5L*60_000L)
                .setBackoffCriteria(60_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .build()
            check(scheduler.schedule(request)==JobScheduler.RESULT_SUCCESS) {
                "Cannot schedule boot-time downloader restoration"
            }
        } catch(t:Exception){
            // Do not crash or ANR Android's boot broadcast. Keep diagnostic
            // evidence; every ensure() also runs through normal app startup.
            Log.e("AsterionRestore","Could not schedule boot restore",t)
        }
    }
}

/** Restores existing services unchanged, outside the broadcast timeout. */
class RestoreDownloadSchedulesJob:JobService() {
    companion object { const val JOB_ID=49371 }
    private val stopped=AtomicBoolean(false)

    override fun onStartJob(params:JobParameters):Boolean {
        stopped.set(false)
        thread(name="asterion-restore-download-schedules") {
            var completed=false
            try {
                val app=applicationContext
                if(AppPower.enabled(app) && !stopped.get()){
                    if(SessionStore.autoSync(app)){
                        Scheduler.ensure(app)
                        Scheduler.runSoon(app)
                    }
                    SourceIndex.ensure(app)
                    CreatorArchiveScheduler.ensure(app)
                    KoikatsuFollowing.ensure(app)
                    RounderDispatch.ensure(app)
                    DirectDownloadRetry.ensure(app)
                    AsterionDownloadWorker.ensure(app)
                    NovelEpubService.ensure(app)
                    Mp4SeriesSync.ensure(app)
                    if(RounderTreeScanner.tree(app)!=null)
                        RounderTreeScanner.scanAsync(app)
                    ChapterRecovery.ensure(app)
                    XPhotoScheduler.ensure(app)
                    NduCloudScheduler.configure(app,NduSettings.enabled(app))
                    com.azrael.galleryflow.Scheduler.ensure(app)
                    com.asterion.videoflow.Scheduling.ensure(app)
                }
                completed=true
            } catch(t:Exception) {
                Log.e("AsterionRestore",
                    "Scheduled restoration failed; retrying without dropping download history",t)
            } finally {
                if(!stopped.get())jobFinished(params,!completed)
            }
        }
        return true
    }

    override fun onStopJob(params:JobParameters):Boolean {
        stopped.set(true)
        return true
    }
}
