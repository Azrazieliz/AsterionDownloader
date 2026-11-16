package com.azrael.pixivdumpsync

import android.content.Context
import java.net.URI
import java.security.MessageDigest

/** Bounded private diagnostics; no poster URLs, titles, cookies or credentials. */
internal object ThumbnailFailureJournal {
    private const val PREF="asterion_thumbnail_failures_v1"
    private val guard=Any()
    private fun host(address:String?):String=runCatching{
        URI(address.orEmpty()).host.orEmpty().lowercase().removePrefix("www.")
    }.getOrDefault("").take(80)
    fun record(context:Context,referer:String?,reason:String){
        val provider=host(referer)
        if(provider.isEmpty())return
        val id=MessageDigest.getInstance("SHA-256")
            .digest(referer.orEmpty().toByteArray(Charsets.UTF_8))
            .take(6).joinToString(""){"%02x".format(it.toInt() and 255)}
        val key="failure:"+provider+":"+id
        val now=System.currentTimeMillis()
        synchronized(guard){
            val prefs=context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            if(now-prefs.getLong("when:"+key,0L)<30_000L)return
            val safe=reason.replace(Regex("""(?i)https?://[^\s'"<>]+"""),"[url]")
                .replace(Regex("""[\r\n\t]+""")," ").take(170)
            prefs.edit().putLong("when:"+key,now)
                .putString(key,now.toString()+" · "+provider+" · "+safe).apply()
        }
    }
    fun report(context:Context,limit:Int=25):String{
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE).all
            .filterKeys{it.startsWith("failure:")}
            .values.mapNotNull{it as? String}
            .sortedDescending().take(limit.coerceIn(1,50))
            .joinToString("\n")
    }
}
