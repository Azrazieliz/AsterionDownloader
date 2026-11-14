package com.asterion.videoflow

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.os.PersistableBundle
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors

object Scheduling {
    private const val ID=42681
    private const val DOWNLOAD_NOW=42682
    private const val DOWNLOAD_RETRY=42683
    private const val SECONDARY_PERIODIC=42684
    private const val SECONDARY_NOW=42685
    private const val SECONDARY_RETRY=42686

    /** Schedule BOTH independently claimed transfers, not just the primary
     * process. Android may reject foreground-service startup from background
     * callbacks, so both processes need their own JobScheduler fallback.
     */
    fun requestRetry(context:Context,delayMs:Long):Boolean{
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val delay=delayMs.coerceIn(2_000L,60L*60_000L)
        val jobs=context.getSystemService(JobScheduler::class.java)
        val primary=jobs.schedule(JobInfo.Builder(DOWNLOAD_RETRY,
            ComponentName(context,PollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay).setOverrideDeadline(delay+30_000L)
            .build())>0
        val secondary=jobs.schedule(JobInfo.Builder(SECONDARY_RETRY,
            ComponentName(context,SecondaryPollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(delay).setOverrideDeadline(delay+30_000L)
            .build())>0
        return primary && secondary
    }
    fun requestPrimaryImmediate(context:Context,scan:Boolean=false,
                                targetHost:String?=null):Boolean{
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val extras=PersistableBundle().apply{
            putBoolean("scan",scan)
            targetHost?.let{putString("target_site_host",it)}
        }
        val job=JobInfo.Builder(DOWNLOAD_NOW,
            ComponentName(context,PollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1000L).setOverrideDeadline(10_000L)
            .setExtras(extras).build()
        return context.getSystemService(JobScheduler::class.java).schedule(job)>0
    }
    fun requestSecondaryImmediate(context:Context):Boolean{
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val job=JobInfo.Builder(SECONDARY_NOW,
            ComponentName(context,SecondaryPollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1000L).setOverrideDeadline(10_000L)
            .build()
        return context.getSystemService(JobScheduler::class.java).schedule(job)>0
    }
    fun requestImmediate(context:Context,scan:Boolean=false,
                         targetHost:String?=null):Boolean{
        val primary=requestPrimaryImmediate(context,scan,targetHost)
        val secondary=requestSecondaryImmediate(context)
        return primary && secondary
    }
    fun configure(context:Context,enabled:Boolean){
        context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
            .edit().putBoolean("auto",enabled).apply()
        val jobs=context.getSystemService(JobScheduler::class.java)
        jobs.cancel(ID)
        jobs.cancel(SECONDARY_PERIODIC)
        if(enabled && com.azrael.pixivdumpsync.AppPower.enabled(context)){
            jobs.schedule(JobInfo.Builder(ID,
                ComponentName(context,PollJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(15*60*1000L)
                .setBackoffCriteria(60*1000L,
                    JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
            jobs.schedule(JobInfo.Builder(SECONDARY_PERIODIC,
                ComponentName(context,SecondaryPollJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).setPeriodic(15*60*1000L)
                .setBackoffCriteria(60*1000L,
                    JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
        }
    }
    fun cancel(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        jobs.cancel(ID)
        jobs.cancel(SECONDARY_PERIODIC)
    }
    fun ensure(context:Context){
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context)){
            cancel(context);return
        }
        if(context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
                .getBoolean("auto",true)){
            val ids=context.getSystemService(JobScheduler::class.java)
                .allPendingJobs.map{it.id}.toSet()
            if(ID !in ids || SECONDARY_PERIODIC !in ids)configure(context,true)
        }
    }
}
class PollJobService: JobService() {
    private val exec=Executors.newSingleThreadExecutor()
    private val tokens=java.util.concurrent.ConcurrentHashMap<Int,Any>()
    private val tasks=java.util.concurrent.ConcurrentHashMap<Int,
        java.util.concurrent.FutureTask<Unit>>()
    override fun onStartJob(params: JobParameters?): Boolean {
        val job=params?:return false
        if(!com.azrael.pixivdumpsync.AppPower.enabled(this))return false
        val manual=job.jobId==42682 || job.jobId==42683
        if(!manual &&
            !getSharedPreferences("video_options",MODE_PRIVATE).getBoolean("auto",true))
            return false
        val scan=if(manual)job.extras.getBoolean("scan",false) else true
        val target=if(manual)job.extras.getString("target_site_host") else null
        // This JobScheduler invocation IS the primary process fallback.
        // Launching both services here could start only the secondary FGS,
        // falsely report success and leave the primary queue lane idle.
        val token=Any()
        val task=java.util.concurrent.FutureTask<Unit> {
            try {
                runCatching{VideoEngine.work(applicationContext,scan,targetHost=target)}
                Unit
            }finally{
                if(tokens.remove(job.jobId,token)) {
                    tasks.remove(job.jobId)
                    if(!Thread.currentThread().isInterrupted)
                        jobFinished(job,false)
                }
            }
        }
        tokens[job.jobId]=token
        tasks.put(job.jobId,task)?.cancel(true)
        exec.execute(task)
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean {
        params?.jobId?.let{id->
            tokens.remove(id)
            tasks.remove(id)?.cancel(true)
        }
        return true
    }
}
// Runs in :videoflow_secondary, independently of the primary process.
// Both agents rely on VideoDb.claimNext() leases; a queued original is owned
// by only one process. Download job completion is not treated as file receipt.
class SecondaryPollJobService:JobService(){
    private val executor=Executors.newSingleThreadExecutor()
    private val tokens=java.util.concurrent.ConcurrentHashMap<Int,Any>()
    private val tasks=java.util.concurrent.ConcurrentHashMap<Int,
        java.util.concurrent.FutureTask<Unit>>()
    override fun onStartJob(params:JobParameters?):Boolean{
        val job=params?:return false
        if(!com.azrael.pixivdumpsync.AppPower.enabled(this))return false
        val manual=job.jobId==42685 || job.jobId==42686
        if(!manual && !getSharedPreferences("video_options",MODE_PRIVATE)
                .getBoolean("auto",true))return false
        val token=Any()
        val task=java.util.concurrent.FutureTask<Unit> {
            try{
                runCatching{VideoEngine.work(applicationContext,false)}
                Unit
            }finally{
                if(tokens.remove(job.jobId,token)) {
                    tasks.remove(job.jobId)
                    if(!Thread.currentThread().isInterrupted)
                        jobFinished(job,false)
                }
            }
        }
        tokens[job.jobId]=token
        tasks.put(job.jobId,task)?.cancel(true)
        executor.execute(task)
        return true
    }
    override fun onStopJob(params:JobParameters?):Boolean{
        params?.jobId?.let{id->
            tokens.remove(id)
            tasks.remove(id)?.cancel(true)
        }
        return true
    }
}

class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if(intent?.action==Intent.ACTION_BOOT_COMPLETED) Scheduling.ensure(context)
    }
}
