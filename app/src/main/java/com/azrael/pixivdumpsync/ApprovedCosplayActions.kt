package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.SourceEntity
import com.azrael.galleryflow.SourceId
import com.azrael.galleryflow.SyncForegroundService
import com.azrael.galleryflow.SyncMode
import java.net.URI
import java.security.MessageDigest

/**
 * The approved Cosplay catalog and its downloaded media database can legitimately
 * be populated at different times. Every visible site action must enroll its
 * approved source before requesting a scan; tapping a media card selects that
 * specific gallery, NEVER an indiscriminate full-site download.
 */
internal object ApprovedCosplayActions {
    private fun approved(address:String):AlohaSite?{
        val host=SiteControls.key(address)
        return AlohaSources.by(AlohaArea.COSPLAY).firstOrNull{
            SiteControls.key(it.url)==host && !ProviderRetirement.isRetired(it.url)
        }
    }
    fun source(context:android.content.Context,address:String):IndexedSource {
        val site=approved(address)
            ?:error("Not an approved Cosplay provider")
        SourceIndexDb(context).use{db->
            val existing=db.sources().firstOrNull{
                it.area=="COSPLAY" &&
                    SiteControls.key(it.url)==SiteControls.key(site.url)
            }
            if(existing!=null)return existing
            // Preserve existing indexed and downloaded history. Registration
            // only creates a missing source; it never resets the saved cursor.
            val native=site.host in AlohaSources.alreadyIntegratedHosts &&
                site.host!="kiutaku.com"
            db.add(site.url,site.name,"COSPLAY",
                if(native)"native" else "generic")
            return db.sources().firstOrNull{it.url==site.url}
                ?:error("Approved Cosplay source could not be enrolled")
        }
    }
    /** An indexed gallery page needs GalleryFlow's selected-album parser.
     * Direct file links still belong to the original-file transfer engine.
     * This distinction is independent of whether the source was previously
     * enrolled or whether a thumbnail was successfully loaded.
     */
    internal fun isGalleryPage(address:String):Boolean {
        if(!SourceIndexParser.safe(address))return false
        val path=runCatching{URI(address).path.orEmpty().trim('/')}
            .getOrDefault("")
        if(path.isBlank())return false
        return !Regex("""(?i)\.(?:jpe?g|png|webp|avif|gif|mp4|webm|mkv|zip|rar|7z)(?:[?#]|$)""")
            .containsMatchIn(address)
    }

    fun backfill(a:Activity,address:String):String {
        if(!AppPower.enabled(a))return "Resume downloads first"
        val enrolled=source(a.applicationContext,address)
        return if(enrolled.adapter=="native")
            ProviderBackfillActions.request(a,enrolled)
        else if(SiteRunControl.start(a.applicationContext,enrolled.url,"COSPLAY"))
            "Gallery history check started · "+enrolled.name
        else "Gallery history already checking · "+enrolled.name
    }

    /**
     * Only the deliberately tapped first-party gallery is admitted to the
     * GalleryFlow native transaction queue. Cover URLs are never treated as
     * completed originals. Subscriptions and automatic Live flags are intact.
     */
    fun selectedGallery(a:Activity,sourceAddress:String,tile:KurohaMediaPreview.Tile):String {
        if(!AppPower.enabled(a))return "Resume downloads first"
        val site=approved(sourceAddress)?:error("Unknown approved Cosplay source")
        require(SourceIndexParser.safe(tile.url) &&
            SourceIndexParser.sameSite(site.url,tile.url)){
            "Gallery selection is not a first-party gallery URL"
        }
        val uri=URI(tile.url)
        val path=uri.path.orEmpty().trim('/')
        require(path.isNotBlank() &&
            path !in setOf("tag","tags","category","categories","search")){
            "Select a specific gallery rather than a website landing page"
        }
        val origin=source(a.applicationContext,site.url)
        // Use the dedicated Kiutaku adapter for numeric gallery detail
        // pages; all other currently approved hosts use their generic
        // first-party gallery adapter for the EXACT selected URL.
        val knownKiutaku=site.host=="kiutaku.com" &&
            path.matches(Regex("[0-9]{1,9}"))
        val entity=if(knownKiutaku){
            com.azrael.galleryflow.AdapterRegistry.forUrl(tile.url)
                .resolveEntity(tile.url)
        }else{
            val digest=MessageDigest.getInstance("SHA-256")
                .digest(tile.url.toByteArray(Charsets.UTF_8))
                .joinToString(""){"%02x".format(it)}
            SourceEntity(SourceId.GENERIC,"url:"+digest.take(32),
                tile.title.take(120).ifBlank{site.name+" gallery"},
                tile.url,"selected_album")
        }
        GalleryDb(a).use{db->
            db.addEntity(entity)
            db.setSelected(entity.source,entity.stableId,true)
            db.setLiveEnabled(entity.source,entity.stableId,false)
        }
        a.startForegroundService(Intent(a,SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name)
            .putExtra(SyncForegroundService.EXTRA_SITE_ID,entity.stableId))
        return "Selected gallery queued · "+site.name+
            " · actual files are verified in Downloads"
    }
}
