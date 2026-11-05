package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.SyncForegroundService as GallerySync
import com.azrael.galleryflow.SyncMode as GalleryMode
import com.asterion.videoflow.VideoEngine
import java.net.URI

/** Retains native provider ownership and existing user include/live switches.
 * Individual generic sites get their own saved pagination cursor; specialist
 * engines continue to own their respective account and transfer databases.
 */
object ProviderBackfillActions {
    fun request(a:Activity,site:IndexedSource):String{
        if(!site.enabled)return "This source is paused. Enable it before backfill."
        val host=runCatching{URI(site.url).host.orEmpty().lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("fr.")}
            .getOrDefault("")
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
            VideoEngine.launch(a,true)
            return "VideoFlow watch scan requested; existing source filters preserved"
        }
        if(site.area=="COSPLAY"){
            val entities=GalleryDb(a).use{db->
                db.listEntities().filter{e->
                    e.selected &&
                    SourceIndexParser.sameSite(site.url,e.canonicalUrl)
                }
            }
            if(entities.isEmpty())return "No included gallery profile for this site"
            for(entity in entities){
                a.startForegroundService(Intent(a,GallerySync::class.java)
                    .setAction(GallerySync.ACTION_START)
                    .putExtra(GallerySync.EXTRA_MODE,GalleryMode.BACKFILL.name)
                    .putExtra(GallerySync.EXTRA_SITE_ID,entity.stableId))
            }
            return "Gallery backfill requested for "+entities.size+" included sources"
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
