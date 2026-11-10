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
        // Both BuonDua domains refer to the same native provider. Keep
        // historical subscriptions but never show independent site switches.
        return when(host){
            "buondua.com"->"buondua.net"
            "omg.xxx"->"omg.porn"
            "novelbuddy.com"->"novelbuddy.me"
            else->host
        }
    }
    fun sync(context:Context,url:String,default:Boolean=false):Boolean {
        val host=key(url)
        if(host.isBlank())return false
        return context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
            .getBoolean("sync:$host",default)
    }
    fun backfill(context:Context,url:String,default:Boolean=false):Boolean =
        sync(context,url,default)

    /** Auto-discovery is not permission to download the site's contents. */
    fun explicitlyEnabled(context:Context,url:String):Boolean {
        val host=key(url)
        if(host.isBlank())return false
        val p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        return p.contains("sync:$host") && p.getBoolean("sync:$host",false)
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
    // One per-site Sync switch controls automatic work. Manual Backfill is
    // a command, not an unrelated always-on toggle.
    fun any(context:Context,url:String)=sync(context,url)

    /** Reflect the single provider setting in each existing engine's saved
     * source-level switches. Run off the UI thread; creator-specific follows,
     * original files, and historical cursors are never deleted or reset.
     */
    fun applyToEngines(context:Context,url:String){
        val app=context.applicationContext
        val host=key(url)
        if(host.isBlank())return
        val live=sync(app,url)
        val historical=live
        val active=live
        runCatching {
            SourceIndexDb(app).use{db->
                db.sources().filter{key(it.url)==host}.forEach{
                    db.enable(it.url,active)
                }
            }
        }
        runCatching{
            com.azrael.galleryflow.GalleryDb(app).use{db->
                db.listEntities().filter{
                    it.kind=="site" && key(it.canonicalUrl)==host
                }.forEach{entity->
                    db.setSelected(entity.source,entity.entityId,active)
                    db.setLiveEnabled(entity.source,entity.entityId,live)
                }
            }
        }
        runCatching{
            com.asterion.videoflow.VideoDb(app).use{db->
                db.listWatches().filter{watch->
                    if(key(watch.address)!=host)return@filter false
                    val path=runCatching{
                        URI(watch.address).path.orEmpty().trim('/')
                    }.getOrDefault("")
                    // Do not overwrite user-disabled actor/model watches.
                    path.isBlank()
                }.forEach{db.enableWatch(it.id,active)}
            }
        }
    }
}
