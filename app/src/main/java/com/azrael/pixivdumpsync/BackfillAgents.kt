package com.azrael.pixivdumpsync

import android.content.Context
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One real logical discovery agent per provider. Independent providers share
 * a device-bounded pool, but duplicate scans of one source are serialized.
 *
 * A duplicate request waits for the CURRENT real scan and any queued rerun.
 * It must never return a fake, already-completed Future while that source is
 * still indexing; otherwise the original dispatcher races unfinished scans.
 */
object BackfillAgents {
    private class Agent {
        val requested=AtomicInteger(0)
        var active:CompletableFuture<Unit>?=null // guarded by synchronized(this)
    }
    private val agents=ConcurrentHashMap<String,Agent>()
    private val pool=Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors()-1).coerceIn(2,8)
    ){r->Thread(r,"asterion-source-agent").apply{isDaemon=true}}
    private const val PREF="asterion_agent_diagnostics"

    fun submit(context:Context,key:String,work:()->Unit):Future<*> {
        val app=context.applicationContext
        val id=key.trim().take(180)
        require(id.isNotBlank())
        val agent=agents.computeIfAbsent(id){Agent()}
        synchronized(agent){
            agent.requested.incrementAndGet()
            agent.active?.takeUnless{it.isDone}?.let{return it}
            val completion=CompletableFuture<Unit>()
            agent.active=completion
            try{
                pool.execute {
                    BackfillPerformance.backgroundWorker()
                    try{
                        while(AppPower.enabled(app) &&
                            !Thread.currentThread().isInterrupted){
                            agent.requested.set(0)
                            val store=app.getSharedPreferences(
                                PREF,Context.MODE_PRIVATE)
                            store.edit().putLong("started:$id",System.currentTimeMillis())
                                .putString("state:$id","RUNNING").apply()
                            val result=runCatching{work()}
                            store.edit()
                                .putLong("finished:$id",System.currentTimeMillis())
                                .putString("state:$id",
                                    if(result.isSuccess)"DONE" else "ERROR")
                                .putString("error:$id",result.exceptionOrNull()
                                    ?.message?.take(360).orEmpty()).apply()
                            synchronized(agent){
                                if(agent.requested.get()==0){
                                    if(agent.active===completion)agent.active=null
                                    completion.complete(Unit)
                                    return@execute
                                }
                            }
                        }
                        app.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                            .putString("state:$id","STOPPED").apply()
                    }catch(t:Throwable){
                        app.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                            .putString("state:$id","ERROR")
                            .putString("error:$id",t.message?.take(360).orEmpty())
                            .apply()
                    }finally{
                        synchronized(agent){
                            if(agent.active===completion)agent.active=null
                            completion.complete(Unit)
                        }
                    }
                }
            }catch(t:RejectedExecutionException){
                if(agent.active===completion)agent.active=null
                completion.completeExceptionally(t)
            }
            return completion
        }
    }

    data class Status(val state:String,val finished:Long,val error:String?)
    fun status(context:Context,key:String):Status{
        val p=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
        return Status(p.getString("state:$key","WAITING").orEmpty(),
            p.getLong("finished:$key",0L),
            p.getString("error:$key",null)?.takeIf{it.isNotBlank()})
    }
}
