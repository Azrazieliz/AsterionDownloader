package com.azrael.pixivdumpsync

import android.app.job.*
import android.content.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Durable periodic creator updates with bounded history pagination. */
object CreatorArchiveScheduler {
    private const val JOB=41082
    private const val CATCHUP=41083
    private const val PAGE_BUDGET=8
    private val scanning=AtomicBoolean(false)
    internal fun scheduleCatchup(context:Context,delayMs:Long=30_000L,force:Boolean=false){
        if(!AppPower.enabled(context))return
        val jobs=context.getSystemService(JobScheduler::class.java)
        if(!force && jobs.allPendingJobs.any{it.id==CATCHUP})return
        jobs.schedule(JobInfo.Builder(CATCHUP,
            ComponentName(context,CreatorArchiveJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs+15L*60_000L)
            .build())
    }
    fun ensure(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        val creators=CreatorArchiveStore.list(context).filter{enabled(context,it)}
        if(creators.isEmpty()){jobs.cancel(JOB);jobs.cancel(CATCHUP);return}
        if(jobs.allPendingJobs.none{it.id==JOB}){
            val request=JobInfo.Builder(JOB,ComponentName(context,CreatorArchiveJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6L*60L*60L*1000L)
                .setPersisted(true)
                .build()
            jobs.schedule(request)
        }
        if(needsCatchup(context))scheduleCatchup(context)
    }
    /** Historical pages or new attachments are unfinished, and no provider error
     * currently requires waiting for the next scheduled refresh.
     */
    internal fun shouldCatchup(complete:Boolean,deferred:Int,error:String):Boolean =
        (!complete || deferred>0) && error.isBlank()
    fun needsCatchup(context:Context):Boolean {
        if(!AppPower.enabled(context))return false
        val prefs=context.getSharedPreferences("asterion_creator_sync",Context.MODE_PRIVATE)
        return CreatorArchiveStore.list(context).any{creator->
            if(!enabled(context,creator))return@any false
            val key="cursor_"+creator.url.hashCode()
            shouldCatchup(
                prefs.getBoolean("complete_"+key,false),
                prefs.getInt("pending_"+key,0),
                prefs.getString("error_"+key,"").orEmpty())
        }
    }
    fun scanNow(context:Context){
        if(!AppPower.enabled(context))return
        thread(name="asterion-creator-refresh"){scan(context)}
    }
    /**
     * Continue historical pages only after *every* attachment on the current page has
     * either been queued or was already successfully/pending downloaded. Advancing the
     * cursor after a 40-file batch silently discarded the remainder of dense pages.
     */
    fun scan(context:Context,shouldStop:()->Boolean={false}){
        if(!AppPower.enabled(context)||!scanning.compareAndSet(false,true))return
        BackfillPerformance.backgroundWorker()
        try{
            val prefs=context.getSharedPreferences("asterion_creator_sync",Context.MODE_PRIVATE)
            var pendingCatchup=false
            for(creator in CreatorArchiveStore.list(context)){
                if(shouldStop()||!AppPower.enabled(context))break
                if(!prefs.getBoolean("active_"+creator.url.hashCode(),true))continue
                val key="cursor_"+creator.url.hashCode()
                var cursor=prefs.getInt(key,0).coerceIn(0,50000)
                var completed=prefs.getBoolean("complete_"+key,false)
                val errors=ArrayList<String>()
                var newlyQueued=0
                var deferred=0

                // Refresh the newest page every run. Its downloads are deduplicated.
                val latest=runCatching{CreatorArchives.posts(creator,0)}
                latest.onSuccess{page->
                    val outcome=CreatorArchiveDownloads.enqueue(context,page,40)
                    newlyQueued+=outcome.queued
                    deferred+=outcome.deferred
                    if(outcome.failed>0)errors+=outcome.failed.toString()+" latest attachments failed"
                    if(cursor==0 && outcome.deferred==0 && outcome.failed==0){
                        if(page.size==50){cursor=50;completed=false}
                        else completed=true
                    }
                }.onFailure{errors+="Latest posts: "+(it.message?:"source unavailable")}

                // Resume up to PAGE_BUDGET historical pages per pass. Keep the existing
                // per-page 40-download ceiling, retry deferred items before advancing,
                // and checkpoint each completed page to survive process termination.
                if(latest.isSuccess && !completed && cursor>0 &&
                    !shouldStop() && AppPower.enabled(context)){
                    var pages=0
                    while(pages<PAGE_BUDGET && !completed && errors.isEmpty() &&
                        !shouldStop() && AppPower.enabled(context)){
                        val history=runCatching{CreatorArchives.posts(creator,cursor)}
                        if(history.isFailure){
                            errors+="Backfill: "+(history.exceptionOrNull()?.message?:"source unavailable")
                            break
                        }
                        val page=history.getOrThrow()
                        val outcome=CreatorArchiveDownloads.enqueue(context,page,40)
                        newlyQueued+=outcome.queued
                        deferred+=outcome.deferred
                        if(outcome.failed>0)
                            errors+=outcome.failed.toString()+" older attachments failed"
                        if(outcome.deferred>0 || outcome.failed>0)break
                        if(page.size<50)completed=true
                        else if(cursor<50000)cursor+=50
                        else {
                            errors+="History API reached maximum supported offset"
                            break
                        }
                        // Cursor points to the next unfinished page, never past it.
                        prefs.edit().putInt(key,cursor)
                            .putBoolean("complete_"+key,completed).apply()
                        pages++
                    }
                }

                // If the archive was completed, recheck the overlapping second page:
                // newly published items may push recently seen posts off the first.
                if(latest.isSuccess && completed && cursor>0 &&
                    !shouldStop() && AppPower.enabled(context)){
                    runCatching{CreatorArchives.posts(creator,50)}.onSuccess{page->
                        val outcome=CreatorArchiveDownloads.enqueue(context,page,40)
                        newlyQueued+=outcome.queued
                        deferred+=outcome.deferred
                        if(outcome.failed>0)errors+=outcome.failed.toString()+" overlap attachments failed"
                    }.onFailure{errors+="Overlap: "+(it.message?:"source unavailable")}
                }
                prefs.edit().putInt(key,cursor).putBoolean("complete_"+key,completed)
                    .putInt("pending_"+key,deferred)
                    .putLong("last_"+key,System.currentTimeMillis())
                    .putInt("queued_"+key,newlyQueued)
                    .putString("error_"+key,errors.joinToString(" | ").take(500)).apply()
                if(shouldCatchup(completed,deferred,errors.joinToString(" | ")) && !shouldStop())
                    pendingCatchup=true
            }
            if(pendingCatchup && !shouldStop())scheduleCatchup(context,2L*60_000L)
        }finally{scanning.set(false)}
    }
    fun enabled(context:Context,creator:ArchiveCreator):Boolean=
        context.getSharedPreferences("asterion_creator_sync",0)
            .getBoolean("active_"+creator.url.hashCode(),true)
    fun enable(context:Context,creator:ArchiveCreator,value:Boolean){
        context.getSharedPreferences("asterion_creator_sync",0)
            .edit().putBoolean("active_"+creator.url.hashCode(),value).apply()
        ensure(context)
    }
    fun status(context:Context,creator:ArchiveCreator):String{
        val prefs=context.getSharedPreferences("asterion_creator_sync",0)
        val key="cursor_"+creator.url.hashCode()
        val at=prefs.getLong("last_"+key,0)
        val error=prefs.getString("error_"+key,"").orEmpty()
        return (if(at==0L)"Not yet checked" else "Last checked "+java.util.Date(at))+
            " · historical offset "+prefs.getInt(key,0)+
            (if(prefs.getBoolean("complete_"+key,false))" · history pages indexed (downloads may be pending)" else " · backfill pending")+
            (if(prefs.getInt("pending_"+key,0)>0)" · "+prefs.getInt("pending_"+key,0)+" files deferred" else "")+
            if(error.isEmpty())"" else " · "+error
    }
}
class CreatorArchiveJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-archive-periodic"){
            try{if(!stopped.get())CreatorArchiveScheduler.scan(applicationContext){stopped.get()}}
            finally{
                jobFinished(params,false)
                // Reschedule after the running catch-up job has finished. Scheduling
                // during scan() alone may see the current job ID still registered.
                if(!stopped.get() &&
                    CreatorArchiveScheduler.needsCatchup(applicationContext))
                    CreatorArchiveScheduler.scheduleCatchup(
                        applicationContext,2L*60_000L,force=true)
            }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
