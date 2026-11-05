package com.azrael.pixivdumpsync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/** Shared direct upload worker for foreground app use and scheduled retries. */
object NduUploadEngine {
    private val inProgress=AtomicBoolean(false)
    data class Result(val uploaded:Int,val attempted:Int,val pending:Int,
                      val connectionError:String?=null)

    fun drain(context:Context, maximum:Int, durationMs:Long,
              stop:()->Boolean={false}, report:(String)->Unit={},
              manual:Boolean=false):Result {
        if(!manual && !AppPower.enabled(context)) return Result(0,0,0,"Downloader paused")
        if(!NduSettings.configured(context)) return Result(0,0,0,"NDUS not configured")
        if(!inProgress.compareAndSet(false,true))
            return Result(0,0,0,"Another direct upload is active")
        try {
            val begin=SystemClock.elapsedRealtime()
            val cookie=NduSettings.cookie(context)
                ?: return Result(0,0,0,"NDUS unavailable")
            NduCloudQueue(context).use { queue ->
                var uploaded=0
                var attempted=0
                val client=NduTeraboxClient(cookie,NduSettings.domain(context))
                try {
                    report("Connecting to TeraBox")
                    client.connect()
                } catch(e:Exception) {
                    return Result(0,0,queue.status().pending,
                        e.message ?: "NDUS session invalid")
                }
                while (attempted<maximum && !stop() &&
                    SystemClock.elapsedRealtime()-begin<durationMs) {
                    val jobs=queue.nextBatch(minOf(30,maximum-attempted))
                    if(jobs.isEmpty()) break
                    for(job in jobs) {
                        if(stop() || SystemClock.elapsedRealtime()-begin>=durationMs) break
                        attempted++
                        try {
                            report("Uploading "+job.category+" / "+job.name)
                            client.upload(context,Uri.parse(job.uri),job.name,
                                NduSettings.destination(context,job.category),
                                cancelled=stop) { done,total ->
                                val percent=if(total>0) done*100/total else 0
                                report("TeraBox "+percent+"% / "+job.name)
                            }
                            queue.completed(job)
                            // A completed remote API response is the first point where
                            // Rounder's durable outbox can truthfully mark an upload complete.
                            RounderOutbox(context).use{it.ack(Uri.parse(job.uri))}
                            uploaded++
                        } catch(t:Throwable) {
                            if(stop() || t is InterruptedException) break
                            queue.failed(job,t.message ?: t.javaClass.simpleName)
                        }
                    }
                }
                val pending=queue.status().pending
                report("Uploaded "+uploaded+" / "+attempted+"; "+pending+" queued")
                return Result(uploaded,attempted,pending)
            }
        } finally { inProgress.set(false) }
    }
}
