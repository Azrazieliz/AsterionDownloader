package com.azrael.pixivdumpsync

import android.content.Context
import com.asterion.videoflow.VideoEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** One independent, cancellable history request per site.
 * A successful hand-off is never reported as completed backfill.
 */
object SiteRunControl {
    private data class Run(val cancelled:AtomicBoolean,val thread:Thread)
    data class Status(val state:String,val message:String,val updated:Long)
    private val active=ConcurrentHashMap<String,Run>()
    private const val PREFS="asterion_site_run_status"
    private fun record(context:Context,host:String,state:String,message:String){
        context.getSharedPreferences(PREFS,0).edit()
            .putString(host+":state",state)
            .putString(host+":message",message.take(220))
            .putLong(host+":updated",System.currentTimeMillis()).apply()
    }
    fun status(context:Context,url:String):Status{
        val host=SiteControls.key(url)
        val p=context.getSharedPreferences(PREFS,0)
        if(active.containsKey(host)){
            val previous=p.getString(host+":state","RUNNING").orEmpty()
            return Status(if(previous=="STOPPING")"STOPPING" else "RUNNING",
                p.getString(host+":message","Checking site history…").orEmpty(),
                p.getLong(host+":updated",0L))
        }
        return Status(p.getString(host+":state","IDLE").orEmpty(),
            p.getString(host+":message","").orEmpty(),
            p.getLong(host+":updated",0L))
    }
    fun stop(context:Context,url:String):Boolean{
        val host=SiteControls.key(url)
        val run=active[host]?:return false
        run.cancelled.set(true)
        record(context,host,"STOPPING","Stopping after the current network request")
        run.thread.interrupt()
        return true
    }
    fun start(context:Context,address:String,area:String):Boolean{
        if(!AppPower.enabled(context))return false
        val host=SiteControls.key(address)
        if(host.isBlank()||active.containsKey(host))return false
        val app=context.applicationContext
        val cancelled=AtomicBoolean(false)
        val job=thread(start=false,name="asterion-history-"+host.take(28)){
            try{
                record(app,host,"RUNNING","Checking recent and older pages")
                val source=SourceIndexDb(app).use{db->
                    db.sources().firstOrNull{SiteControls.key(it.url)==host && it.area==area}
                }
                if(source?.adapter=="native"){
                    VideoEngine.scanSite(app,address,area){cancelled.get() ||
                        Thread.currentThread().isInterrupted}
                }else{
                    if(source==null)
                        SourceIndexDb(app).use{it.add(address,host,area,"generic")}
                    SourceIndex.scanSingle(app,address){cancelled.get() ||
                        Thread.currentThread().isInterrupted}
                }
                if(cancelled.get())record(app,host,"STOPPED","Stopped; cursor and files retained")
                else record(app,host,"DONE","Source checked; indexed items are not downloaded files")
            }catch(e:Exception){
                if(cancelled.get()||e is InterruptedException)
                    record(app,host,"STOPPED","Stopped; retry remains available")
                else record(app,host,"ERROR",e.message?:"Source check failed")
            }finally{
                active.remove(host)
            }
        }
        val run=Run(cancelled,job)
        if(active.putIfAbsent(host,run)!=null)return false
        job.start()
        return true
    }
}
