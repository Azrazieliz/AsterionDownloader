package com.azrael.pixivdumpsync

import android.content.Context
import android.os.SystemClock
import java.io.IOException
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Per-host courteous EPUB request pacing and server-directed 429 cooldown.
 * Durable cooldown avoids hammering the same blocked host after a JobService
 * restart. Separate hosts can still download on independent workers.
 */
internal class NovelRateLimited(val retryAtEpochMs:Long):
    IOException("Novel source HTTP 429; retry scheduled after server cooldown")

internal object NovelHttpPacing {
    private const val PREFS="asterion_epub_host_rate_limit_v1"
    private const val MIN_GAP_MS=1_750L
    private const val FALLBACK_MS=2L*60_000L
    private const val MAX_COOLDOWN_MS=24L*60L*60_000L
    private val locks=ConcurrentHashMap<String,Any>()
    private val recent=ConcurrentHashMap<String,Long>()

    private fun host(url:String):String {
        val uri=URI(url)
        require(uri.scheme in setOf("https","http") && uri.host!=null)
        return uri.host.lowercase(Locale.ROOT)
    }
    /** Parse both legal Retry-After formats; clamp untrusted huge values. */
    fun retryDelayMillis(value:String?,nowMs:Long):Long {
        val raw=value?.trim().orEmpty()
        val seconds=raw.toLongOrNull()
        val requested=if(seconds!=null){
            if(seconds<=0L)0L else if(seconds>86_400L)
                MAX_COOLDOWN_MS else seconds*1_000L
        } else {
            runCatching{
                ZonedDateTime.parse(raw,DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant().toEpochMilli()-nowMs
            }.getOrDefault(FALLBACK_MS)
        }
        return requested.coerceIn(FALLBACK_MS,MAX_COOLDOWN_MS)
    }
    fun recordRateLimit(context:Context,url:String,retryAfter:String?):Long {
        val key=host(url)
        val now=System.currentTimeMillis()
        val until=now+retryDelayMillis(retryAfter,now)
        val guard=locks.getOrPut(key){Any()}
        synchronized(guard){
            val prefs=context.applicationContext.getSharedPreferences(
                PREFS,Context.MODE_PRIVATE)
            val current=prefs.getLong(key,0L)
            val end=maxOf(current,until)
            prefs.edit().putLong(key,end).commit()
            return end
        }
    }
    fun beforeRequest(context:Context,url:String){
        val key=host(url)
        val guard=locks.getOrPut(key){Any()}
        synchronized(guard){
            val remaining=context.applicationContext.getSharedPreferences(
                PREFS,Context.MODE_PRIVATE).getLong(key,0L)-
                System.currentTimeMillis()
            if(remaining>0L)
                throw NovelRateLimited(System.currentTimeMillis()+remaining)
            val now=SystemClock.elapsedRealtime()
            val delay=((recent[key]?:0L)+MIN_GAP_MS-now)
                .coerceIn(0L,MIN_GAP_MS)
            if(delay>0L)try{Thread.sleep(delay)}
                catch(t:InterruptedException){
                    Thread.currentThread().interrupt()
                    throw IOException("EPUB download interrupted",t)
                }
            recent[key]=SystemClock.elapsedRealtime()
        }
    }
}
