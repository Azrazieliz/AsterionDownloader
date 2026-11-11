package com.azrael.pixivdumpsync

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Durable manual per-provider Backfill is distinct from automatic Sync.
 * Each pass advances the existing native/source-index cursor. Android may
 * stop a worker; pending requests resume through the existing job system.
 * A site is never labelled completed unless its own scanner says so.
 */
object SiteRunControl {
    private data class Run(val cancelled:AtomicBoolean,val thread:Thread)
    data class Status(val state:String,val message:String,val updated:Long)
    private val active=ConcurrentHashMap<String,Run>()
    private const val PREFS="asterion_site_run_status"
    private const val JOB_ID=49891
    private const val PENDING="manual_requests"
    private fun key(url:String)=url.trimEnd('/').lowercase()
    private fun token(area:String,url:String)=area+"\t"+key(url)
    private fun decode(value:String):Pair<String,String>? {
        val split=value.indexOf('\t')
        if(split<1)return null
        val area=value.substring(0,split)
        val url=value.substring(split+1)
        if(area !in setOf("ADULT","HENTAI","VIDEOS","CREATORS","COSPLAY",
                "ASSETS","STUDIO","MMD","LIVE2D","ANIMATION","MODS","BOOKS",
                "COMICS") || !SourceIndexParser.safe(url))return null
        return area to url
    }
    @Synchronized private fun requests(context:Context):Set<String> =
        context.getSharedPreferences(PREFS,0)
            .getStringSet(PENDING,emptySet()).orEmpty().toSet()
    @Synchronized private fun changeRequest(
        context:Context,area:String,url:String,requested:Boolean){
        val p=context.getSharedPreferences(PREFS,0)
        val values=p.getStringSet(PENDING,emptySet()).orEmpty().toMutableSet()
        if(requested)values.add(token(area,url))
        else values.remove(token(area,url))
        p.edit().putStringSet(PENDING,values).commit()
    }
    private fun pendingFor(context:Context,url:String):Boolean=
        requests(context).any{it.substringAfter('\t',"")==key(url)}
    fun hasPending(context:Context)=requests(context).isNotEmpty()
    private fun record(context:Context,url:String,state:String,message:String){
        val id=key(url)
        context.getSharedPreferences(PREFS,0).edit()
            .putString(id+":state",state)
            .putString(id+":message",message.take(220))
            .putLong(id+":updated",System.currentTimeMillis()).apply()
    }
    fun status(context:Context,url:String):Status{
        val id=key(url)
        val prefs=context.getSharedPreferences(PREFS,0)
        val saved=prefs.getString(id+":state","IDLE").orEmpty()
        val state=when{
            active.containsKey(id)->if(saved=="STOPPING")"STOPPING" else "RUNNING"
            pendingFor(context,url)->"QUEUED"
            saved in setOf("RUNNING","STOPPING")->"INTERRUPTED"
            else->saved
        }
        return Status(state,
            prefs.getString(id+":message","").orEmpty(),
            prefs.getLong(id+":updated",0L))
    }

    /** Prefer the exact profile/source URL; avoid mistaking a host for a URL.
     * Never switch to a different creator merely because they share a host.
     */
    internal fun matchingSource(
        address:String,area:String,sources:List<IndexedSource>
    ):IndexedSource? {
        val eligible=sources.filter{it.area==area}
        val exact=eligible.firstOrNull{key(it.url)==key(address)}
        if(exact!=null)return exact
        val related=eligible.filter{
            SiteControls.key(it.url)==SiteControls.key(address)
        }
        return related.singleOrNull()
    }

    /** User Stop removes the persisted work request, not its cursor or files. */
    fun stop(context:Context,url:String):Boolean{
        val app=context.applicationContext
        val wasPending=requests(app).filter{
            it.substringAfter('\t',"")==key(url)
        }
        for(entry in wasPending)decode(entry)?.let{
            changeRequest(app,it.first,it.second,false)
        }
        val run=active[key(url)]
        if(run!=null){
            run.cancelled.set(true)
            record(app,url,"STOPPING","Stopping the current network request")
            run.thread.interrupt()
        }else if(wasPending.isNotEmpty()){
            record(app,url,"STOPPED","Stopped; history cursor and files retained")
        }
        return run!=null || wasPending.isNotEmpty()
    }

    fun start(context:Context,address:String,area:String):Boolean{
        if(!AppPower.enabled(context) ||
            area !in setOf("ADULT","HENTAI","VIDEOS","CREATORS","COSPLAY",
                "ASSETS","STUDIO","MMD","LIVE2D","ANIMATION","MODS",
                "BOOKS","COMICS") || !SourceIndexParser.safe(address))return false
        val app=context.applicationContext
        if(active.containsKey(key(address)))return false
        changeRequest(app,area,address,true)
        record(app,address,"QUEUED","Manual historical backfill requested")
        val launched=spawn(app,address,area)
        if(!launched)scheduleIfPending(app)
        return launched
    }

    private fun currentSource(context:Context,address:String,area:String)=
        SourceIndexDb(context).use{matchingSource(address,area,it.sources())}

    private fun runOne(context:Context,address:String,area:String,
                       stop:()->Boolean):Boolean {
        if(stop() || !AppPower.enabled(context))return false
        var source=currentSource(context,address,area)
        val generic=source?.adapter=="generic" ||
            (source==null && area !in setOf("ADULT","HENTAI","VIDEOS","CREATORS"))
        val target=source?.url?:address
        val before=if(generic)source?.cursor else
            VideoDb(context).use{db->
                db.listWatches().firstOrNull{key(it.address)==key(target)}
                    ?.cursor
            }
        if(generic){
            if(source==null){
                SourceIndexDb(context).use{it.add(target,
                    SiteControls.key(target),area,"generic")}
                source=currentSource(context,target,area)
            }
            require(source!=null){"Unable to register this provider"}
            SourceIndex.scanSingle(context,source.url,stop)
        }else{
            if(source==null){
                SourceIndexDb(context).use{
                    it.add(target,SiteControls.key(target),area,"native")
                    // Manual Backfill must not silently subscribe a site to
                    // automatic Sync; only its explicit Sync switch can do that.
                    it.enable(target,SiteControls.sync(context,target,false))
                }
            }
            VideoEngine.scanSite(context,target,area,stop)
            if(!SiteControls.sync(context,target,false))
                VideoDb(context).use{db->
                    db.listWatches().filter{key(it.address)==key(target)}
                        .forEach{db.enableWatch(it.id,false)}
                }
        }
        if(stop() || !AppPower.enabled(context))return false
        val (cursor,complete)=if(generic){
            val status=currentSource(context,target,area)
                ?:error("Source state disappeared after backfill")
            if(!status.error.isNullOrBlank())error(status.error)
            status.cursor to status.historyComplete
        }else{
            val watches=VideoDb(context).use{it.listWatches()}
            val watch=watches.firstOrNull{key(it.address)==key(target)}
                ?:watches.singleOrNull{
                    SiteControls.key(it.address)==SiteControls.key(target)
                }?:error("Video provider did not retain its history state")
            if(!watch.error.isNullOrBlank())error(watch.error)
            watch.cursor to watch.backfillComplete
        }
        if(complete)return true
        require(!cursor.isNullOrBlank() && cursor!=before){
            "History cursor did not advance; provider parser or access needs repair"
        }
        return false
    }

    private fun spawn(context:Context,address:String,area:String):Boolean{
        val id=key(address)
        val cancelled=AtomicBoolean(false)
        val worker=thread(start=false,
            name="asterion-manual-site-"+SiteControls.key(address).take(24)){
            runBound(context,address,area,cancelled,
                externalStop={Thread.currentThread().isInterrupted})
        }
        if(active.putIfAbsent(id,Run(cancelled,worker))!=null)return false
        worker.start()
        return true
    }

    private fun runBound(context:Context,address:String,area:String,
                         cancelled:AtomicBoolean,externalStop:()->Boolean,
                         reschedule:Boolean=true){
        val id=key(address)
        val thisThread=Thread.currentThread()
        val acquired=active[id]?.thread==thisThread
        if(!acquired && active.putIfAbsent(
            id,Run(cancelled,thisThread))!=null)return
        val request=token(area,address)
        try{
            if(request !in requests(context))return
            record(context,address,"RUNNING","Checking history and original files")
            val complete=runOne(context,address,area){
                cancelled.get() || externalStop()
            }
            when{
                cancelled.get()->{
                    changeRequest(context,area,address,false)
                    record(context,address,"STOPPED","Stopped; files and cursor retained")
                }
                externalStop() || !AppPower.enabled(context)->{
                    record(context,address,"QUEUED","Interrupted; retained cursor")
                }
                complete->{
                    changeRequest(context,area,address,false)
                    record(context,address,"CHECKED",
                        "Historical index complete; file status is in Downloads")
                }
                else->{
                    record(context,address,"QUEUED",
                        "Older pages remain; next history pass scheduled")
                }
            }
        }catch(error:Exception){
            if(cancelled.get()){
                changeRequest(context,area,address,false)
                record(context,address,"STOPPED","Stopped; files and cursor retained")
            }else if(externalStop() || error is InterruptedException){
                record(context,address,"QUEUED","Interrupted; retained cursor")
            }else{
                changeRequest(context,area,address,false)
                record(context,address,"ERROR",
                    error.message?:"Provider could not complete its history check")
            }
        }finally{
            active.remove(id)
            // A JobService schedules its next pass after jobFinished; the
            // direct UI thread schedules here. Both use the same saved queue.
            if(reschedule && !externalStop() && AppPower.enabled(context))
                scheduleIfPending(context)
        }
    }

    /** Invoked on a JobScheduler background thread, never from the UI. */
    fun resumePending(context:Context,stop:()->Boolean){
        val pending=requests(context).sorted().mapNotNull{entry->
            decode(entry)
        }.take(3)
        for((area,address) in pending){
            if(stop() || !AppPower.enabled(context))break
            runBound(context,address,area,AtomicBoolean(false),stop,false)
        }
    }

    fun scheduleIfPending(context:Context,delayMs:Long=90_000L,
                          force:Boolean=false){
        if(!AppPower.enabled(context) || !hasPending(context))return
        val scheduler=context.getSystemService(JobScheduler::class.java)
        if(!force && scheduler.allPendingJobs.any{it.id==JOB_ID})return
        val delay=delayMs.coerceIn(5000L,6L*60L*60_000L)
        scheduler.schedule(JobInfo.Builder(JOB_ID,
            ComponentName(context,SiteRunJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setMinimumLatency(delay)
            .setOverrideDeadline(delay+5L*60_000L)
            .build())
    }
}
