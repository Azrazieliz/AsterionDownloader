package com.azrael.pixivdumpsync

import android.content.Context
import java.net.URI
import java.util.Locale

/**
 * One logical provider, one independently controlled Sync / Backfill pair.
 * All existing native watchers and archived entries retain their own metadata.
 * Changing these switches NEVER deletes source records or historical cursors.
 */
object SiteControls {
    private const val PREFS="asterion_site_controls_v1"

    /** Canonicalize site aliases, not albums, pages, models or categories. */
    fun key(url:String):String {
        val host=runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("").removePrefix("www.").removePrefix("m.")
            .removePrefix("fr.").removePrefix("en.")
        return host
    }
    fun sync(context:Context,url:String):Boolean {
        val host=key(url)
        if(host.isBlank())return false
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean("sync:$host",true)
    }
    fun backfill(context:Context,url:String):Boolean {
        val host=key(url)
        if(host.isBlank())return false
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean("backfill:$host",true)
    }
    fun setSync(context:Context,url:String,enabled:Boolean){
        val host=key(url)
        require(host.isNotBlank())
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean("sync:$host",enabled).apply()
    }
    fun setBackfill(context:Context,url:String,enabled:Boolean){
        val host=key(url)
        require(host.isNotBlank())
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean("backfill:$host",enabled).apply()
    }
    fun any(context:Context,url:String)=sync(context,url)||backfill(context,url)
}
