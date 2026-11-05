package com.azrael.pixivdumpsync

import android.content.Context
import android.os.Process
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One durable logical agent per provider/engine. Dispatch is always async.
 * Every approved provider is submitted: no arbitrary first-five/first-twenty
 * site limits. Active threads share a device-aware pool to avoid ANRs/OOM;
 * historical scans, downloads and saved cursors are never truncated by it.
 */
object BackfillAgents {
    private data class Agent(val busy:AtomicBoolean=AtomicBoolean(false),
                             val requested:AtomicInteger=AtomicInteger(0))
    private val agents=ConcurrentHashMap<String,Agent>()
    private val pool=Executors.newFixedThreadPool(
        (Runtime.getRuntime().availableProcessors()-1).coerceIn(2,8)
    ){r->Thread(r,"asterion-source-agent").apply{isDaemon=true}}
    private const val PREF="asterion_agent_diagnostics"

    fun submit(context:Context,key:String,work:()->Unit):Future<*>{
        val app=context.applicationContext
        val id=key.trim().take(180)
        require(id.isNotBlank())
        val agent=agents.computeIfAbsent(id){Agent()}
        agent.requested.incrementAndGet()
        if(!agent.busy.compareAndSet(false,true)){
            // The current agent will perform one additional scan. Do not
            // create overlapping workers for the same site/cursor.
            return pool.submit(Callable { Unit })
        }
        return pool.submit {
            BackfillPerformance.backgroundWorker()
            try {
                do {
                    agent.requested.set(0)
                    if(!AppPower.enabled(app))break
                    val store=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
                    store.edit().putLong("started:$id",System.currentTimeMillis())
                        .putString("state:$id","RUNNING").apply()
                    val result=runCatching { work() }
                    store.edit().putLong("finished:$id",System.currentTimeMillis())
                        .putString("state:$id",if(result.isSuccess)"DONE" else "ERROR")
                        .putString("error:$id",result.exceptionOrNull()?.message
                            ?.take(360).orEmpty()).apply()
                }while(agent.requested.get()>0 && AppPower.enabled(app))
            }finally {
                agent.busy.set(false)
                // If a new request arrived at the completion boundary,
                // restart its owner rather than losing the request.
                if(agent.requested.get()>0 && AppPower.enabled(app))
                    submit(app,id,work)
            }
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
