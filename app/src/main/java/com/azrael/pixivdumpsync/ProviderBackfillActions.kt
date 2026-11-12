package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.SyncForegroundService as GallerySync
import com.azrael.galleryflow.SyncMode as GalleryMode
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.VideoDb
import java.net.URI

/** Retains native provider ownership and existing user include/live switches.
 * Individual generic sites get their own saved pagination cursor; specialist
 * engines continue to own their respective account and transfer databases.
 */
object ProviderBackfillActions {
    fun request(a:Activity,site:IndexedSource):String{
        if(!AppPower.enabled(a))return "App is paused in Settings"
        val host=SiteControls.key(site.url)
        if(host in setOf("onejav.com","sukebei.nyaa.si")){
            TorrentSourceBackfill.scanAsync(a)
            return "Torrent historical metadata check requested"
        }
        if(host=="mp4directs.com"){
            Mp4SeriesSync.ensure(a)
            Mp4SeriesSync.scanNow(a)
            return "Subscribed MP4Directs series scan requested"
        }
        if(site.adapter=="generic"){
            SourceIndex.scanSourceNow(a.applicationContext,site.url)
            return "Historical page scan requested for "+site.name
        }
        if(site.area in setOf("ADULT","VIDEOS","HENTAI")){
            // Manual history acquisition is separate from the Sync switch.
            VideoDb(a).use{db->
                db.addWatch(site.url,site.name)
                db.listWatches().filter{
                    it.address.trimEnd('/')==site.url.trimEnd('/')
                }.forEach{db.resetWatchBackfill(it.id)}
            }
            val accepted=VideoEngine.launch(a,true,host)
            return if(accepted)"Video scan queued for "+host
                else "Unable to start video check for "+host
        }
        if(site.area=="COSPLAY"){
            // Approved native sites may be registered in SourceIndex before
            // GalleryFlow has ever saved a native entity. Explicit BACKFILL
            // must enroll the actual site profile, not silently do nothing.
            val exists=GalleryDb(a).use{db->db.listEntities().any{
                SiteControls.key(it.canonicalUrl)==host
            }}
            if(!exists){
                val enrolled=runCatching{
                    val adapter=com.azrael.galleryflow.AdapterRegistry.forUrl(site.url)
                    val entity=adapter.resolveEntity(site.url)
                    GalleryDb(a).use{it.addEntity(entity)}
                }
                if(enrolled.isFailure)
                    return "Provider profile unavailable · "+
                        (enrolled.exceptionOrNull()?.message?:"adapter needs repair")
            }
            val selected=GalleryDb(a).use{db->
                val matched=db.listEntities().filter{entity->
                    SiteControls.key(entity.canonicalUrl)==host
                }
                // An explicit BACKFILL is allowed even when automatic Sync
                // is off. Do not switch automatic Live back on.
                for(entity in matched){
                    db.setSelected(entity.source,entity.entityId,true)
                    db.setLiveEnabled(entity.source,entity.entityId,
                        SiteControls.explicitlyEnabled(a,site.url))
                }
                matched.size
            }
            if(selected==0)return "Site has no native gallery profile; use its dedicated adapter or connect account"
            // Manual history acquisition is independent of live Sync, never
            // downgraded to a new-items check due to the Sync switch.
            val mode=GalleryMode.BACKFILL
            a.startForegroundService(Intent(a,GallerySync::class.java)
                .setAction(GallerySync.ACTION_START)
                .putExtra(GallerySync.EXTRA_MODE,mode.name)
                .putExtra(GallerySync.EXTRA_SITE_HOST,host))
            return "Scheduled "+host+" · "+mode.name.lowercase()+" · "+
                selected+" included profiles"
        }
        if(site.area=="BOOKS" && NovelEpubParser.supportedSeries(site.url)){
            NovelEpubService.enqueue(a,site.url,site.name)
            return "EPUB series historical extraction requested"
        }
        if(site.area=="CREATORS"){
            CreatorArchiveScheduler.scanNow(a)
            return "Creator archive check requested"
        }
        if(site.area=="ASSETS"){
            KoikatsuFollowing.ensure(a)
            return "Asset source scheduler checked"
        }
        SourceIndex.scanSourceNow(a.applicationContext,site.url)
        return "Source check requested"
    }
}
