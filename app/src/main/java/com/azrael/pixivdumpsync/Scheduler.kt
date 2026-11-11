package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object Scheduler {
    private const val PERIODIC_JOB_ID = 7751
    private const val IMMEDIATE_JOB_ID = 7752
    private const val FIFTEEN_MINUTES = 15L * 60L * 1000L

    fun ensure(context: Context) {
        if(!AppPower.enabled(context)){cancel(context);return}
        if (
            !AppPower.enabled(context) ||
            !SessionStore.autoSync(context) ||
            !SessionStore.followingFeedEnabled(context) ||
            !SessionStore.isLoggedIn(context)
        ) {
            cancel(context)
            return
        }

        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.allPendingJobs.any { it.id == PERIODIC_JOB_ID }) return

        val job = JobInfo.Builder(
            PERIODIC_JOB_ID,
            ComponentName(context, SyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(FIFTEEN_MINUTES)
            .build()

        scheduler.schedule(job)
    }

    fun runSoon(context: Context) {
        if(!AppPower.enabled(context))return
        if (
            !AppPower.enabled(context) ||
            !SessionStore.autoSync(context) ||
            !SessionStore.followingFeedEnabled(context) ||
            !SessionStore.isLoggedIn(context)
        ) return

        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.allPendingJobs.any { it.id == IMMEDIATE_JOB_ID }) return

        val job = JobInfo.Builder(
            IMMEDIATE_JOB_ID,
            ComponentName(context, SyncJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1_000L)
            .setOverrideDeadline(60_000L)
            .setBackoffCriteria(
                30L * 1000L,
                JobInfo.BACKOFF_POLICY_EXPONENTIAL
            )
            .build()

        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).apply {
            cancel(PERIODIC_JOB_ID)
            cancel(IMMEDIATE_JOB_ID)
        }
    }
}
