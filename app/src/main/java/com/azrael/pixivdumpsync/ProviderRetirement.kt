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
    private val retired=setOf("chaturbate.com","onscreens.me","cums.net","pornhits.com","omg.porn","omg.xxx","recu.me",
        "porntrex.com","xnxx.com","xvideos.com","peekvids.com","tnaflix.com",
        "mp4hentai.com","hentaihaven.xxx","helloporn.com")

    fun isRetired(address:String):Boolean {
        val uri=runCatching { URI(address) }.getOrNull() ?: return false
        val host=uri.host?.lowercase(Locale.ROOT) ?: return false
        return retired.any { host==it || host.endsWith("."+it) }
    }

    /** Idempotent and data-preserving: disable existing subscriptions only. */
    fun disableExisting(context:Context) {
        val app=context.applicationContext
        val pref=app.getSharedPreferences("asterion_provider_retirements_v1",0)
        // Archivebate is explicitly restored by the owner. Older releases
        // disabled saved Archivebate subscriptions through this migration
        // ledger; restore them once without disturbing any other provider.
        // Never rerun the migration after the owner changes follow settings.
        if(pref.getBoolean("archivebate.com-disabled",false) &&
            !pref.getBoolean("archivebate.com-reactivated-v1",false)){
            fun isArchivebate(address:String):Boolean {
                val host=runCatching { URI(address).host.orEmpty()
                    .lowercase(Locale.ROOT) }.getOrDefault("")
                return host=="archivebate.com" ||
                    host.endsWith(".archivebate.com")
            }
            val indexed=runCatching {
                SourceIndexDb(app).use { db->
                    db.sources().filter{isArchivebate(it.url)}.forEach {
                        db.enable(it.url,true)
                    }
                }
            }.isSuccess
            val watched=runCatching {
                com.asterion.videoflow.VideoDb(app).use { db->
                    db.listWatches().filter{isArchivebate(it.address)}
                        .forEach{db.enableWatch(it.id,true)}
                }
            }.isSuccess
            if(indexed && watched)
                pref.edit().putBoolean("archivebate.com-reactivated-v1",true).apply()
        }
        // CamStreams is explicitly reinstated by the owner in Nov 2026.
        // An earlier retirement disabled its saved watch/index records.
        // Restore only subscriptions the user has enabled; never globally
        // re-enable a user-paused source, replay cancelled downloads, or
        // clear any prior downloaded original.
        if(pref.getBoolean("camstreams.tv-disabled",false) &&
            !pref.getBoolean("camstreams.tv-reactivated-v1",false)){
            val host="camstreams.tv"
            val optedIn=SiteControls.explicitlyEnabled(
                app,"https://camstreams.tv/latest-updates/")
            val indexed=runCatching{
                SourceIndexDb(app).use{ db->
                    db.sources().filter{SiteControls.key(it.url)==host}
                        .forEach{db.enable(it.url,optedIn)}
                }
            }.isSuccess
            val watched=runCatching{
                com.asterion.videoflow.VideoDb(app).use{db->
                    db.listWatches().filter{SiteControls.key(it.address)==host}
                        .forEach{db.enableWatch(it.id,optedIn)}
                }
            }.isSuccess
            if(indexed && watched)
                pref.edit().putBoolean("camstreams.tv-reactivated-v1",true).apply()
        }
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
