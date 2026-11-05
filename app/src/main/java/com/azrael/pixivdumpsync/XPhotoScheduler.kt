package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle

object XPhotoScheduler {
    private const val ID=28914
    private const val IMMEDIATE=28915
    fun requestImmediate(context:Context,automatic:Boolean=false,targetSource:String?=null):Boolean{
        if(!AppPower.enabled(context))return false
        val extras=PersistableBundle().apply{
            putBoolean("automatic",automatic)
            if(!targetSource.isNullOrBlank())putString("source",targetSource)
        }
        return context.getSystemService(JobScheduler::class.java).schedule(
            JobInfo.Builder(IMMEDIATE,ComponentName(context,XPhotoJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(1000L)
                .setOverrideDeadline(15000L)
                .setExtras(extras).build())>0
    }
    /** Round-robin by source, including all 513+ accounts without blocking photos
     * behind 513 sequential remote-site requests on every app start.
     */
    fun selectBatch(context:Context,sources:List<XSourceRecord>,batch:Int=10):List<XSourceRecord>{
        if(sources.size<=batch)return sources
        val prefs=context.getSharedPreferences("asterion_x_batch_cursor",0)
        val start=prefs.getInt("next",0).coerceAtLeast(0)%sources.size
        prefs.edit().putInt("next",(start+batch)%sources.size).apply()
        return (0 until batch).map{sources[(start+it)%sources.size]}
    }
    fun cancel(context:Context){
        context.getSystemService(JobScheduler::class.java).cancel(ID)
        context.getSystemService(JobScheduler::class.java).cancel(IMMEDIATE)
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
        val immediate=params?.jobId==28915
        val automatic=if(immediate)params?.extras?.getBoolean("automatic",false)?:false
            else true
        val target=if(immediate)params?.extras?.getString("source") else null
        // The JobService itself may run in the background, where FGS is denied.
        if(XPhotoEngine.start(this,automatic,target,allowJobFallback=false))return false
        val task=XPhotoEngine.submit(applicationContext,automatic,target)
        Thread{try{runCatching{task.get()}}finally{jobFinished(params,false)}}.start()
        return true
    }
    override fun onStopJob(params:JobParameters?):Boolean=true
}
