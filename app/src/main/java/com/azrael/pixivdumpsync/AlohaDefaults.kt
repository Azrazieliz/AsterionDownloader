package com.azrael.pixivdumpsync

import android.content.Context
import android.content.Intent
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.Scheduler
import com.azrael.galleryflow.SourceId
import com.azrael.galleryflow.SyncForegroundService
import com.azrael.galleryflow.SyncMode
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.Scheduling
import java.net.URI
import kotlin.concurrent.thread

/** Opted-in Aloha domain defaults, reusing Kyora and VideoFlow's original DBs.
 * One-time backfill reset is per source; a later user pause/disable stays disabled.
 * Sites without a working native parser remain catalogued, not falsely completed.
 */
object AlohaDefaults {
    private const val PREF="asterion_approved_sources_20261101"
    private val galleryAreas=setOf(AlohaArea.COSPLAY)
    private val videoAreas=setOf(AlohaArea.ADULT,AlohaArea.HENTAI)
    private fun host(url:String):String=runCatching{
        URI(url).host.orEmpty().lowercase().removePrefix("www.")
    }.getOrDefault("")
    fun ensure(context:Context){
        val app=context.applicationContext
        thread(name="asterion-approved-source-seed"){
            val prefs=app.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            var newVideo=false
            var newGallery=false
            VideoDb(app).use{db->
                for(site in AlohaSources.all.filter{it.area in videoAreas}){
                    val key="video_"+site.host
                    if(prefs.getBoolean(key,false))continue
                    runCatching{
                        db.addWatch(site.url,site.name)
                        val watch=db.listWatches().firstOrNull{host(it.address)==site.host}
                            ?:error("Video provider was not saved")
                        db.enableWatch(watch.id,true)
                        db.resetWatchBackfill(watch.id)
                        prefs.edit().putBoolean(key,true).remove("error_"+key).apply()
                        newVideo=true
                    }.onFailure{prefs.edit().putString("error_"+key,it.message.orEmpty()).apply()}
                }
            }
            GalleryDb(app).use{db->
                val existing=db.listEntities()
                for(site in AlohaSources.all.filter{it.area in galleryAreas}){
                    val key="gallery_"+site.host
                    if(prefs.getBoolean(key,false))continue
                    runCatching{
                        val present=existing.filter{
                            host(it.canonicalUrl)==site.host
                        }
                        if(present.isNotEmpty()){
                            for(item in present){
                                db.setSelected(item.source,item.entityId,true)
                                db.setLiveEnabled(item.source,item.entityId,true)
                                db.setLiveCursor(item.source,item.entityId,null)
                            }
                        }else{
                            val adapter=AdapterRegistry.forUrl(site.url)
                            val entity=adapter.resolveEntity(site.url)
                            db.addEntity(entity)
                            db.setSelected(entity.source,entity.stableId,true)
                            db.setLiveEnabled(entity.source,entity.stableId,true)
                        }
                        prefs.edit().putBoolean(key,true).remove("error_"+key).apply()
                        newGallery=true
                    }.onFailure{prefs.edit().putString("error_"+key,
                        (it.message?:"Source unavailable").take(280)).apply()}
                }
            }
            // A source's homepage may not support backfill; preserve the source and
            // its error instead of claiming that indexing the homepage completed it.
            if(AppPower.enabled(app)){
                if(newVideo){
                    runCatching{Scheduling.ensure(app);VideoEngine.launch(app,true)}
                }
                if(newGallery){
                    runCatching{
                        Scheduler.ensure(app)
                        app.startForegroundService(Intent(app,SyncForegroundService::class.java)
                            .setAction(SyncForegroundService.ACTION_START)
                            .putExtra(SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name))
                    }.onFailure{
                        prefs.edit().putString("gallery_start_error",it.message.orEmpty()).apply()
                    }
                }
            }
        }
    }
    fun seeded(context:Context,site:AlohaSite):Boolean{
        val key=when(site.area){
            AlohaArea.COSPLAY->"gallery_"+site.host
            AlohaArea.ADULT,AlohaArea.HENTAI->"video_"+site.host
            else->return false
        }
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getBoolean(key,false)
    }
    fun error(context:Context,site:AlohaSite):String?{
        val key=when(site.area){
            AlohaArea.COSPLAY->"gallery_"+site.host
            AlohaArea.ADULT,AlohaArea.HENTAI->"video_"+site.host
            else->return null
        }
        return context.getSharedPreferences(PREF,Context.MODE_PRIVATE)
            .getString("error_"+key,null)
    }
}
