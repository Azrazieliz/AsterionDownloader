package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

object XPhotoScheduler {
    private const val ID=28914
    fun cancel(context:Context){
        context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
    fun ensure(context:Context){
        if(!AppPower.enabled(context)){cancel(context);return}
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(scheduler.allPendingJobs.any{it.id==ID})return
        scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,XPhotoJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true).setPeriodic(15*60*1000L).build())
    }
}
class XPhotoJob:JobService(){
    override fun onStartJob(params:JobParameters?):Boolean {
        if(!AppPower.enabled(this))return false
        if(!XPhotoQueue(this).use{it.sources().isNotEmpty()})return false
        // Android can block background foreground-service launches.
        if(runCatching{XPhotoEngine.start(this,true)}.isSuccess)return false
        val task=XPhotoEngine.submit(applicationContext,true)
        Thread{runCatching{task.get()};jobFinished(params,false)}.start()
        return true
    }
    override fun onStopJob(params:JobParameters?):Boolean=true
}
