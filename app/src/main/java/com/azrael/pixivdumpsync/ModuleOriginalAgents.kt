package com.azrael.pixivdumpsync

import android.content.Context
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
                            try {
                                val submitted=SourceIndex.submitOriginal(app,item)
                                SourceIndexDb(app).use{db->
                                    if(submitted)db.markQueued(item.url)
                                    else db.defer(item.url)
                                }
                            }catch(_:Exception){
                                SourceIndexDb(app).use{it.defer(item.url)}
                            }finally{
                                SourceIndexDb(app).use{it.releaseDirect(item.url)}
                            }
                            if(handled>=40)break
                        }
                        if(!acquired)break
                    }
                }finally{
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
