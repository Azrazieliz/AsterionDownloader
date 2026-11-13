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
    // Live FreeWebNovel reader test received HTTP 429 by its eighth chapter.
    // Its long-reader route needs a slower per-host cadence; parallel workers
    // must share this schedule instead of issuing rapid bursts.
    internal fun minimumGapMillis(host:String):Long=when(host.removePrefix("www.")){
        "freewebnovel.com"->10_000L
        "novelbuddy.me","novelbuddy.com"->4_000L
        else->MIN_GAP_MS
    }
    internal fun repeatedLimitDelayMillis(strikes:Int):Long=
        when(strikes.coerceAtLeast(1)){
            1->120_000L
            2->300_000L
            3->900_000L
            4->1_800_000L
            else->3_600_000L
        }
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
        val guard=locks.getOrPut(key){Any()}
        synchronized(guard){
            val prefs=context.applicationContext.getSharedPreferences(
                PREFS,Context.MODE_PRIVATE)
            val previousAt=prefs.getLong(key+"#last429",0L)
            // A repeated 429 is evidence of an ongoing provider restriction,
            // not a reason for aggressive retries. Decay after a full day.
            val strikes=if(now-previousAt<24L*60*60_000L)
                (prefs.getInt(key+"#strikes",0)+1).coerceAtMost(20)
                else 1
            val requested=retryDelayMillis(retryAfter,now)
            val delay=maxOf(requested,repeatedLimitDelayMillis(strikes))
            val current=prefs.getLong(key,0L)
            val end=maxOf(current,now+delay)
            check(prefs.edit().putLong(key,end)
                .putInt(key+"#strikes",strikes)
                .putLong(key+"#last429",now).commit()){
                "Cannot persist EPUB publisher cooldown"
            }
            return end
        }
    }
    /**
     * Serialize the ENTIRE HTTP exchange for the same publisher. Previously
     * the lock protected only the start-time check; a slow request and a
     * second novel worker could overlap and trigger another HTTP 429 before
     * the first response's Retry-After was persisted.
     */
    fun <T> serializedRequest(context:Context,url:String,fetch:()->T):T {
        val guard=locks.getOrPut(host(url)){Any()}
        synchronized(guard){
            beforeRequest(context,url)
            return fetch()
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
            val minimum=minimumGapMillis(key)
            val delay=((recent[key]?:0L)+minimum-now)
                .coerceIn(0L,minimum)
            if(delay>0L)try{Thread.sleep(delay)}
                catch(t:InterruptedException){
                    Thread.currentThread().interrupt()
                    throw IOException("EPUB download interrupted",t)
                }
            recent[key]=SystemClock.elapsedRealtime()
        }
    }
}
