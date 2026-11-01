package com.azrael.pixivdumpsync

import android.app.job.*
import android.content.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Durable periodic creator updates with bounded history pagination. */
object CreatorArchiveScheduler {
    private const val JOB=41082
    private val scanning=AtomicBoolean(false)
    fun ensure(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        val enabled=CreatorArchiveStore.list(context).any{creator->enabled(context,creator)}
        if(!enabled){jobs.cancel(JOB);return}
        if(jobs.allPendingJobs.any{it.id==JOB})return
        val request=JobInfo.Builder(JOB,ComponentName(context,CreatorArchiveJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(6L*60L*60L*1000L)
            .setPersisted(true)
            .build()
        jobs.schedule(request)
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
        try{
            val prefs=context.getSharedPreferences("asterion_creator_sync",Context.MODE_PRIVATE)
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

                // A saved historical cursor is the next *uncompleted* page, not the
                // next page after an arbitrary batch. Resume it on subsequent runs.
                if(latest.isSuccess && !completed && cursor>0 &&
                    !shouldStop() && AppPower.enabled(context)){
                    val history=runCatching{CreatorArchives.posts(creator,cursor)}
                    history.onSuccess{page->
                        val outcome=CreatorArchiveDownloads.enqueue(context,page,40)
                        newlyQueued+=outcome.queued
                        deferred+=outcome.deferred
                        if(outcome.failed>0)errors+=outcome.failed.toString()+" older attachments failed"
                        if(outcome.deferred==0 && outcome.failed==0){
                            if(page.size<50)completed=true
                            else if(cursor<50000)cursor+=50
                            else completed=true
                        }
                    }.onFailure{errors+="Backfill: "+(it.message?:"source unavailable")}
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
            }
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
            (if(prefs.getBoolean("complete_"+key,false))" · full history checked" else " · backfill pending")+
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
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
