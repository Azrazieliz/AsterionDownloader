package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Schedules recovery after network returns or Android restarts the app. */
object ChapterRecovery {
    private const val JOB_ID=28913
    fun ensure(context:Context){
        val manager=context.getSystemService(JobScheduler::class.java)
        if(manager.allPendingJobs.any{it.id==JOB_ID})return
        manager.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context,ChapterRecoveryService::class.java))
            .setPersisted(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(15*60*1000L)
            .build())
    }
}
class ChapterRecoveryService:JobService(){
    override fun onStartJob(params:JobParameters?):Boolean {
        val pending=ChapterQueue(this).use{db->
            db.recover()
            db.retryDueFailures()
            db.refreshDue()
            db.next()!=null
        }
        if(pending)runCatching{ChapterArchives.start(this)}
        return false
    }
    override fun onStopJob(params:JobParameters?):Boolean=false
}
