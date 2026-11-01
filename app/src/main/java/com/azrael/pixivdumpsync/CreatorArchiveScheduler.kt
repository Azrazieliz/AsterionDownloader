package com.azrael.pixivdumpsync

import android.app.job.*
import android.content.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Durable periodic creator updates with bounded history pagination. */
object CreatorArchiveScheduler {
    private const val JOB=41082
    fun ensure(context:Context){
        val jobs=context.getSystemService(JobScheduler::class.java)
        val enabled=CreatorArchiveStore.list(context).isNotEmpty()
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
    fun scan(context:Context){
        if(!AppPower.enabled(context))return
        val prefs=context.getSharedPreferences("asterion_creator_sync",Context.MODE_PRIVATE)
        val selected=CreatorArchiveStore.list(context)
        for(creator in selected){
            if(!AppPower.enabled(context))break
            if(!prefs.getBoolean("active_"+creator.url.hashCode(),true))continue
            val key="cursor_"+creator.url.hashCode()
            val previous=prefs.getInt(key,0)
            val errors=ArrayList<String>()
            var newlyQueued=0
            val offsets=listOf(0,previous,previous+50,previous+100).distinct()
            for(offset in offsets){
                if(offset>50000)break
                val page=runCatching{CreatorArchives.posts(creator,offset)}
                page.onSuccess{posts->
                    if(posts.isEmpty()){
                        if(offset>0)prefs.edit().putBoolean("complete_"+key,true).apply()
                    }else{
                        val outcome=CreatorArchiveDownloads.enqueue(context,posts,40)
                        newlyQueued+=outcome.queued
                        if(outcome.failed>0)errors+=outcome.failed.toString()+" attachments could not queue"
                        if(offset>0 && posts.size==50)
                            prefs.edit().putInt(key,offset+50).apply()
                        if(offset>0 && posts.size<50)
                            prefs.edit().putBoolean("complete_"+key,true).apply()
                    }
                }.onFailure{errors+=it.message?:"Source unavailable"}
                if(page.isFailure)break
            }
            prefs.edit().putLong("last_"+key,System.currentTimeMillis())
                .putInt("queued_"+key,newlyQueued)
                .putString("error_"+key,errors.joinToString(" | ").take(500)).apply()
        }
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
            if(error.isEmpty())"" else " · "+error
    }
}
class CreatorArchiveJob:JobService(){
    private val stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{
        stopped.set(false)
        thread(name="asterion-archive-periodic"){
            try{if(!stopped.get())CreatorArchiveScheduler.scan(applicationContext)}
            finally{jobFinished(params,false)}
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        stopped.set(true)
        return true
    }
}
