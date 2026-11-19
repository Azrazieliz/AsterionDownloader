package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Periodic network-only retry for a durable upload queue. */
object NduCloudScheduler {
    private const val JOB_ID=9321
    private const val NOW_ID=9322
    /** Wake the genuine cloud transfer worker now, not at the next
     * 15-minute periodic upload. The platform may defer the job. */
    fun requestImmediate(ctx:Context):Boolean = requestAfter(ctx,1_000L)
    fun requestAfter(ctx:Context,delayMs:Long):Boolean {
        if(!NduSettings.configured(ctx) || !AppPower.enabled(ctx))return false
        val delay=delayMs.coerceIn(1_000L,60L*60_000L)
        val job=JobInfo.Builder(NOW_ID,
            ComponentName(ctx,NduCloudJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay+30_000L)
            .build()
        return ctx.getSystemService(JobScheduler::class.java)
            .schedule(job)==JobScheduler.RESULT_SUCCESS
    }
    fun configure(ctx:Context, enabled:Boolean) {
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        if(!enabled || !AppPower.enabled(ctx)) {
            scheduler.cancel(JOB_ID)
            scheduler.cancel(NOW_ID)
            return
        }
        val info=JobInfo.Builder(JOB_ID,ComponentName(ctx,NduCloudJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15*60_000L)
            .setPersisted(true)
            .build()
        scheduler.schedule(info)
    }
}

class NduCloudJobService : JobService() {
    private val executor=Executors.newSingleThreadExecutor()
    private var running:Future<*>?=null
    override fun onStartJob(params:JobParameters):Boolean {
        if(!NduSettings.configured(this)) return false
        running=executor.submit {
            var nextRemoteDue:Long?=null
            try {
                runCatching{
                    TeraBoxRemoteQueue(this).use{queue->
                        queue.drain(this,24){
                            Thread.currentThread().isInterrupted ||
                                !AppPower.enabled(this)
                        }
                        nextRemoteDue=queue.nextDueDelayMs()
                    }
                }.onFailure{
                    // A failed connection does not consume a queued cloud
                    // original. Retry from the existing encrypted ledger.
                    nextRemoteDue=120_000L
                }
                if(!NduSettings.cloudOnly(this)){
                    NduUploadEngine.drain(this,100,7*60_000L,
                        stop={Thread.currentThread().isInterrupted})
                }
            } finally {
                if(!Thread.currentThread().isInterrupted &&
                    AppPower.enabled(this))nextRemoteDue?.let{
                    NduCloudScheduler.requestAfter(this,it.coerceAtLeast(30_000L))
                }
                jobFinished(params,false)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {
        running?.cancel(true)
        return true
    }
    override fun onDestroy() {
        running?.cancel(true)
        executor.shutdownNow()
        super.onDestroy()
    }
}
