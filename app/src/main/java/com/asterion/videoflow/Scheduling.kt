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
    fun requestImmediate(context:Context,scan:Boolean=false,targetHost:String?=null):Boolean {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context))return false
        val extras=PersistableBundle().apply {
            putBoolean("scan",scan)
            targetHost?.let{putString("target_site_host",it)}
        }
        val job=JobInfo.Builder(DOWNLOAD_NOW,ComponentName(context,PollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setMinimumLatency(1000L)
            .setOverrideDeadline(10_000L)
            .setExtras(extras)
            .build()
        return context.getSystemService(JobScheduler::class.java).schedule(job)>0
    }
    fun configure(context: Context, enabled: Boolean) {
        context.getSharedPreferences("video_options",Context.MODE_PRIVATE).edit().putBoolean("auto",enabled).apply()
        val scheduler=context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(ID)
        if(enabled && com.azrael.pixivdumpsync.AppPower.enabled(context))
            scheduler.schedule(JobInfo.Builder(ID,ComponentName(context,PollJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15*60*1000L)
            .setBackoffCriteria(60*1000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build())
    }
    fun cancel(context:Context){
        context.getSystemService(JobScheduler::class.java).cancel(ID)
    }
    fun ensure(context: Context) {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(context)){cancel(context);return}
        if(context.getSharedPreferences("video_options",Context.MODE_PRIVATE).getBoolean("auto",true)) {
            val scheduler=context.getSystemService(JobScheduler::class.java)
            if(scheduler.allPendingJobs.none { it.id==ID }) configure(context,true)
        }
    }
}
class PollJobService: JobService() {
    private val exec=Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters?): Boolean {
        if(!com.azrael.pixivdumpsync.AppPower.enabled(this))return false
        val manual=params?.jobId==42682
        if(!manual &&
            !getSharedPreferences("video_options",MODE_PRIVATE).getBoolean("auto",true))
            return false
        val scan=if(manual)params?.extras?.getBoolean("scan",false)?:false else true
        val target=if(manual)params?.extras?.getString("target_site_host") else null
        if(VideoEngine.launch(applicationContext,scan,target,allowJobFallback=false))
            return false
        exec.submit {
            try {
                // JobScheduler is permitted when Android forbids starting the FGS.
                runCatching{VideoEngine.work(applicationContext,scan,targetHost=target)}
            }finally{
                jobFinished(params,false)
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters?): Boolean = true
}
class BootReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if(intent?.action==Intent.ACTION_BOOT_COMPLETED) Scheduling.ensure(context)
    }
}
