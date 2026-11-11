package com.azrael.pixivdumpsync

import android.content.Context
import java.net.URI
import java.util.Locale

/**
 * A provider retired by the user must not silently reappear from old
 * watch/index databases. Keep all existing media, downloads and history.
 */
object ProviderRetirement {
    private val retired=setOf("pornhits.com")

    fun isRetired(address:String):Boolean {
        val uri=runCatching { URI(address) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        return retired.any { host==it || host.endsWith("."+it) }
    }

    /** Idempotent and data-preserving: disable existing subscriptions only. */
    fun disableExisting(context:Context) {
        val app=context.applicationContext
        val pref=app.getSharedPreferences("asterion_provider_retirements_v1",0)
        if(pref.getBoolean("pornhits-disabled",false))return
        // Use independent transactions so one unavailable database does not
        // accidentally re-enable the other provider's work.
        val indexed=runCatching {
            SourceIndexDb(app).use { db->
                db.sources().filter { isRetired(it.url) }.forEach {
                    db.enable(it.url,false)
                }
            }
        }.isSuccess
        val watched=runCatching {
            com.asterion.videoflow.VideoDb(app).use { db->
                db.listWatches().filter { isRetired(it.address) }.forEach {
                    db.enableWatch(it.id,false)
                }
                db.pauseProvider("pornhits.com")
            }
        }.isSuccess
        if(indexed && watched)
            pref.edit().putBoolean("pornhits-disabled",true).apply()
    }
}
