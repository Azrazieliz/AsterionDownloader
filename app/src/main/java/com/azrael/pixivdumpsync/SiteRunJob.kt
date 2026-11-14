package com.azrael.pixivdumpsync

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Resumes independently requested manual provider backfills after a process
 * restart, Android job preemption or a large history scan's bounded page pass.
 */
class SiteRunJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-manual-provider-backfill"){
            try{
                SiteRunControl.resumePending(applicationContext){stopped.get() ||
                    Thread.currentThread().isInterrupted}
            }finally{
                jobFinished(params,false)
                if(!stopped.get())SiteRunControl.scheduleIfPending(applicationContext,force=true)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
