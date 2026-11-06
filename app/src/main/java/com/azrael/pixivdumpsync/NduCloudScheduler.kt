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
    fun configure(ctx:Context, enabled:Boolean) {
        val scheduler=ctx.getSystemService(JobScheduler::class.java)
        if(!enabled || !AppPower.enabled(ctx)) {
            scheduler.cancel(JOB_ID)
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
            try {
                NduUploadEngine.drain(this,100,7*60_000L,
                    stop={Thread.currentThread().isInterrupted})
            } finally {
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
