package com.azrael.pixivdumpsync

import android.content.Context
import java.net.URI
import java.util.Locale

/**
 * A provider retired by the user must not silently reappear from old
 * watch/index databases. Keep all existing media, downloads and history.
 */
object ProviderRetirement {
    // Both OMG aliases expose the same public 34/34 video identifiers as
    // FreePornVideos in the 2026-11-11 verified listing comparison.
    private val retired=setOf("pornhits.com","omg.porn","omg.xxx",
        "porntrex.com","xnxx.com")

    fun isRetired(address:String):Boolean {
        val uri=runCatching { URI(address) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        return retired.any { host==it || host.endsWith("."+it) }
    }

    /** Idempotent and data-preserving: disable existing subscriptions only. */
    fun disableExisting(context:Context) {
        val app=context.applicationContext
        val pref=app.getSharedPreferences("asterion_provider_retirements_v1",0)
        // Migrate retirement one provider at a time. The old builds may
        // have already saved pornhits-disabled=true; that must never cause
        // newly retired OMG aliases to be skipped.
        for(domain in retired){
            val key=if(domain=="pornhits.com")"pornhits-disabled"
                else "$domain-disabled"
            // Recheck every enrollment pass, including previously retired hosts.
            fun belongs(address:String):Boolean {
                val host=runCatching{URI(address).host.orEmpty()
                    .lowercase(Locale.ROOT)}.getOrDefault("")
                return host==domain || host.endsWith("."+domain)
            }
            val indexed=runCatching {
                SourceIndexDb(app).use { db->
                    db.sources().filter{belongs(it.url)}.forEach{
                        db.enable(it.url,false)
                    }
                }
            }.isSuccess
            val watched=runCatching {
                com.asterion.videoflow.VideoDb(app).use { db->
                    db.listWatches().filter{belongs(it.address)}.forEach{
                        db.enableWatch(it.id,false)
                    }
                    db.pauseProvider(domain)
                }
            }.isSuccess
            if(indexed && watched)
                pref.edit().putBoolean(key,true).apply()
        }
    }
}
