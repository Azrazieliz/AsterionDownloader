package com.azrael.pixivdumpsync

import android.content.Context
import android.app.DownloadManager
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two real asynchronous original-file dispatch agents per media module.
 *
 * Each agent exclusively claims one durable source-index entry in SQLite,
 * routes it to VideoFlow, the book/comic engines or Android DownloadManager,
 * and releases the claim. For HTTP originals DownloadManager owns the byte
 * transfer; enqueued is not counted as a completed file. VideoFlow itself
 * has two native-process transfer agents with independent Python runtimes.
 *
 * Lazy per-module pools avoid unnecessary idle foreground services.
 */
internal object ModuleOriginalAgents {
    const val AGENTS_PER_MODULE=2
    val MODULES=setOf(
        "ADULT","HENTAI","VIDEOS","CREATORS","COSPLAY","ARTWORK",
        "BOOKS","COMICS","GAMES","ASSETS","STUDIO","MMD",
        "LIVE2D","ANIMATION","MODS","VOICE"
    )
    private class Lane(area:String){
        val requested=AtomicBoolean(false)
        val active=AtomicBoolean(false)
        val remaining=AtomicInteger(0)
        val executor:ExecutorService=Executors.newFixedThreadPool(
            AGENTS_PER_MODULE){r->
                Thread(r,"asterion-"+area.lowercase()+"-original-agent").apply{
                    isDaemon=true
                }
            }
    }
    private val lanes=ConcurrentHashMap<String,Lane>()
    private val userRequests=ConcurrentHashMap.newKeySet<String>()
    /** Explicit user-requested original retrieval shares the same pair of
     * workers as automated requests in that module, never spawning a third
     * unbounded ad-hoc downloader thread on every repeated tap.
     */
    fun submitUser(context:Context,area:String,identity:String,
                   operation:()->Unit):Boolean{
        if(area !in MODULES || !AppPower.enabled(context))return false
        val key=area+":"+identity
        if(!userRequests.add(key))return false
        val lane=lanes.computeIfAbsent(area){Lane(area)}
        return try{
            lane.executor.submit{
                try{operation()}
                finally{userRequests.remove(key)}
            }
            true
        }catch(_:java.util.concurrent.RejectedExecutionException){
            userRequests.remove(key)
            false
        }
    }
    private val PREF="asterion_module_original_agents_v1"
    private fun status(context:Context,area:String,slot:Int,value:String){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
            .putString(area+":"+slot,
                System.currentTimeMillis().toString()+"|"+value).apply()
    }
    fun states(context:Context):Map<String,String>{
        val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        return MODULES.flatMap{area->
            (0 until AGENTS_PER_MODULE).map{slot->
                val key=area+":"+slot
                key to prefs.getString(key,"IDLE").orEmpty()
            }
        }.toMap()
    }
    private fun folder(area:String):String=when(area){
        "BOOKS"->"Books";"COMICS"->"Comics";"GAMES"->"Games"
        "CREATORS"->"Creators";"COSPLAY"->"Cosplay";"ARTWORK"->"Artwork"
        "STUDIO"->"Assets/Koikatsu Studio";"MMD"->"Assets/MMD"
        "LIVE2D"->"Assets/Live2D";"ANIMATION"->"Assets/Animation"
        "MODS"->"Games/Mods";"VOICE"->"Audio/Doujin Voice"
        else->"Assets"
    }
    private fun activeDirect(context:Context,area:String):Int{
        val category=folder(area)
        return DirectDownloadTracker.states(context,220).count{state->
            state.job.folder==category && state.status in setOf(
                DownloadManager.STATUS_PENDING,DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED)
        }
    }
    private fun monitor(context:Context,area:String,slot:Int,id:Long){
        val manager=context.getSystemService(DownloadManager::class.java)
        val deadline=SystemClock.elapsedRealtime()+15L*60_000L
        while(AppPower.enabled(context) &&
            !Thread.currentThread().isInterrupted &&
            SystemClock.elapsedRealtime()<deadline){
            val statusValue=manager.query(
                DownloadManager.Query().setFilterById(id))?.use{cursor->
                if(cursor.moveToFirst()){
                    val index=cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if(index>=0)cursor.getInt(index) else null
                }else null
            }
            if(statusValue==DownloadManager.STATUS_FAILED){
                status(context,area,slot,"SYSTEM_FAILED")
                return
            }
            if(statusValue==DownloadManager.STATUS_SUCCESSFUL){
                val receipt=manager.getUriForDownloadedFile(id)
                val valid=receipt!=null && runCatching{
                    context.contentResolver.openFileDescriptor(receipt,"r")?.use{
                        it.statSize>0L
                    }==true
                }.getOrDefault(false)
                status(context,area,slot,if(valid)"SAVED_RECEIPT" else "BAD_RECEIPT")
                return
            }
            if(statusValue==null){
                status(context,area,slot,"SYSTEM_REQUEST_NOT_FOUND")
                return
            }
            status(context,area,slot,"TRANSFER_IN_FLIGHT")
            Thread.sleep(2_500L)
        }
        status(context,area,slot,"SYSTEM_TRANSFER_CONTINUES")
    }

    fun start(context:Context,area:String){
        if(area !in MODULES || !AppPower.enabled(context))return
        val app=context.applicationContext
        val lane=lanes.computeIfAbsent(area){Lane(area)}
        lane.requested.set(true)
        if(!lane.active.compareAndSet(false,true))return
        lane.requested.set(false)
        lane.remaining.set(AGENTS_PER_MODULE)
        repeat(AGENTS_PER_MODULE){slot->
            lane.executor.submit {
                BackfillPerformance.backgroundWorker()
                status(app,area,slot,"STARTED")
                try {
                    var handled=0
                    while(AppPower.enabled(app) && handled<40 &&
                        !Thread.currentThread().isInterrupted){
                        val due=SourceIndexDb(app).use{
                            it.pendingDirect(60,area)
                        }
                        if(due.isEmpty())break
                        var acquired=false
                        for(item in due){
                            if(!AppPower.enabled(app))break
                            if(!SourceIndex.originalRequested(app,item))continue
                            val claimed=SourceIndexDb(app).use{
                                it.claimDirect(item.url)
                            }
                            if(!claimed)continue
                            acquired=true
                            handled++
                            var downloadId:Long?=null
                            var accepted=false
                            try {
                                // Enforce at most two active DownloadManager
                                // HTTP transfers per module. The media SDK may
                                // use its own scheduler; we do not overfill it.
                                synchronized(lane){
                                    val isVideo=item.kind=="video"
                                    if(isVideo || activeDirect(app,area)<AGENTS_PER_MODULE){
                                        accepted=SourceIndex.submitOriginal(app,item){id->
                                            downloadId=id
                                        }
                                        if(accepted)SourceIndexDb(app).use{
                                            it.markQueued(item.url)
                                        }else SourceIndexDb(app).use{
                                            it.defer(item.url)
                                        }
                                    }
                                }
                            }catch(_:Exception){
                                SourceIndexDb(app).use{it.defer(item.url)}
                            }finally{
                                SourceIndexDb(app).use{it.releaseDirect(item.url)}
                            }
                            if(accepted && downloadId!=null){
                                monitor(app,area,slot,downloadId!!)
                            }else if(!accepted && downloadId==null &&
                                activeDirect(app,area)>=AGENTS_PER_MODULE){
                                status(app,area,slot,"TWO_DOWNLOADS_ALREADY_ACTIVE")
                                break
                            }
                            if(handled>=40)break
                        }
                        if(!acquired)break
                    }
                }finally{
                    status(app,area,slot,"IDLE")
                    if(lane.remaining.decrementAndGet()==0){
                        lane.active.set(false)
                        if(lane.requested.getAndSet(false) &&
                            AppPower.enabled(app))start(app,area)
                    }
                }
            }
        }
    }

    fun startAll(context:Context,area:String?=null){
        if(area!=null){start(context,area);return}
        val areas=SourceIndexDb(context).use{db->
            MODULES.filter{m->db.pendingDirect(1,m).isNotEmpty()}
        }
        for(m in areas)start(context,m)
    }
}
