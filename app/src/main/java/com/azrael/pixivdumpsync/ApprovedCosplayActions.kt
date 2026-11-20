package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.GalleryRequestLedger
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

    /** One stable provider entity. Never create extra site records for
     * every tapped photo/cover; individual albums remain separate galleries. */
    internal fun albumRoot(host:String,name:String):SourceEntity?=when(host){
        "kiutaku.com"->SourceEntity(SourceId.KIUTAKU,
            "site:kiutaku.com",name,"https://kiutaku.com/","site")
        "pornpics.com"->SourceEntity(SourceId.GENERIC,
            "site:pornpics.com",name,"https://www.pornpics.com/","site")
        else->null
    }

    private fun enqueueAlbumRun(a:Activity,entity:SourceEntity,
                                sourceAddress:String,mode:SyncMode){
        GalleryDb(a).use{db->
            db.addEntity(entity)
            db.setSelected(entity.source,entity.stableId,true)
            // Manual commands work even when automatic Sync is OFF, but
            // must never silently turn scheduled Live checks ON.
            db.setLiveEnabled(entity.source,entity.stableId,
                SiteControls.sync(a,sourceAddress,false))
        }
        val host=SiteControls.key(sourceAddress)
        GalleryRequestLedger.remember(a,GalleryRequestLedger.Request(
            mode,entity.stableId,siteHost=host,
            siteSource=entity.source.wireName))
        a.startForegroundService(Intent(a,SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE,mode.name)
            .putExtra(SyncForegroundService.EXTRA_SITE_ID,entity.stableId)
            .putExtra(SyncForegroundService.EXTRA_SITE_HOST,host)
            .putExtra(SyncForegroundService.EXTRA_SITE_SOURCE,
                entity.source.wireName))
    }

    /** Real Kyora root for ALL approved cosplay providers, including
     * generic-only PornPics/Celeb.st. A preview/index refresh is NOT a
     * backfill, and a generic legacy row must never replace a native root.
     */
    private fun galleryRoot(a:Activity,enrolled:IndexedSource):SourceEntity {
        val host=SiteControls.key(enrolled.url)
        albumRoot(host,enrolled.name)?.let{return it}
        val adapter=com.azrael.galleryflow.AdapterRegistry.forUrl(
            enrolled.url,a.applicationContext)
        GalleryDb(a).use{db->
            db.listEntities().firstOrNull{record->
                record.kind=="site" && record.source==adapter.source &&
                    SiteControls.key(record.canonicalUrl)==host
            }?.let{record->
                return SourceEntity(record.source,record.entityId,
                    record.displayName,record.canonicalUrl,"site")
            }
        }
        return adapter.defaultEntities().firstOrNull{entity->
            entity.kind=="site" &&
                SiteControls.key(entity.canonicalUrl)==host
        }?:adapter.resolveEntity(enrolled.url)
    }

    fun checkNew(a:Activity,address:String):String{
        if(!AppPower.enabled(a))return "Resume downloads first"
        val enrolled=source(a.applicationContext,address)
        val root=runCatching{galleryRoot(a,enrolled)}.getOrElse{
            return "Gallery adapter unavailable · "+enrolled.name+" · "+
                (it.message?:"Could not register source").take(120)
        }
        enqueueAlbumRun(a,root,enrolled.url,SyncMode.LIVE)
        return "Original Kyora Check New queued · "+enrolled.name+
            " · wait for verified gallery files"
    }

    fun backfill(a:Activity,address:String):String {
        if(!AppPower.enabled(a))return "Resume downloads first"
        val enrolled=source(a.applicationContext,address)
        val entity=runCatching{galleryRoot(a,enrolled)}.getOrElse{
            return "Gallery adapter unavailable · "+enrolled.name+" · "+
                (it.message?:"Could not register source").take(120)
        }
        enqueueAlbumRun(a,entity,enrolled.url,SyncMode.BACKFILL)
        return "Full-site Kyora original album backfill queued · "+enrolled.name+
            " · individual gallery failures remain visible for retry"
    }

    /**
     * Only the deliberately tapped first-party gallery is admitted to the
     * GalleryFlow native transaction queue. Cover URLs are never treated as
     * completed originals. Subscriptions and automatic Live flags are intact.
     */
    internal fun exactKiutakuAlbum(address:String,title:String):SourceEntity {
        val uri=URI(address)
        val id=uri.path.orEmpty().trim('/')
        require(uri.scheme in setOf("http","https") &&
            SiteControls.key(address)=="kiutaku.com" &&
            id.matches(Regex("[0-9]{1,9}"))){
            "Expected a Kiutaku numeric gallery"
        }
        return SourceEntity(SourceId.KIUTAKU,"gallery:"+id,
            title.take(120).ifBlank{"Kiutaku gallery "+id},
            "https://kiutaku.com/"+id,"gallery_only")
    }

    fun selectedGallery(a:Activity,sourceAddress:String,tile:KurohaMediaPreview.Tile):String {
        if(!AppPower.enabled(a))return "Resume downloads first"
        val site=approved(sourceAddress)?:error("Unknown approved Cosplay source")
        require(SourceIndexParser.safe(tile.url) &&
            SiteControls.key(site.url)==SiteControls.key(tile.url)){
            // BuonDua .com catalog cards and .net original albums belong to
            // the SAME native provider; raw-host matching rejected valid
            // Kyora selected albums before they ever reached GalleryFlow.
            "Gallery selection is not a first-party gallery URL"
        }
        val uri=URI(tile.url)
        val path=uri.path.orEmpty().trim('/')
        require(path.isNotBlank() &&
            path !in setOf("tag","tags","category","categories","search")){
            "Select a specific gallery rather than a website landing page"
        }
        // A PornPics category/channel link or Kiutaku profile/tag is not
        // an album. Do not persist phantom galleries when tapped.
        require(when(site.host){
            "pornpics.com"->path.matches(Regex("""(?i)galleries/[^/]+"""))
            "kiutaku.com"->path.matches(Regex("""[0-9]{1,9}"""))
            "4khd.com","cosplaytele.com"->
                SiteBackfillProfiles.recognizesGalleryPage(site.url,tile.url)
            else->true
        }){"Select an individual "+site.name+" album, not a category or source"}
        val origin=source(a.applicationContext,site.url)
        // An album is NOT a provider. Reuse the site's existing durable
        // subscription; only the gallery record gets an individual identity.
        // Do not invoke resolveEntity(album), which can turn one post into a
        // creator/tag subscription or spawn an extra "generic" provider card.
        val entity=GalleryDb(a).use{db->
            // Never force a selected 4KHD / Buondua / CosplayTele album
            // through GenericSiteAdapter when its original native parser is
            // already installed. That discarded provider-specific archives,
            // full-image routing and the original download ledger.
            val nativeSource=com.azrael.galleryflow.AdapterRegistry.forUrl(
                site.url,a.applicationContext).source
            // A generic legacy site record is NOT interchangeable with
            // Kyora's original native site adapter. Selecting it sent
            // 4KHD/CosplayTele/BuonDua into the wrong parser, which yielded
            // previews, HTTP 403 or no actual download. Keep legacy rows
            // intact, but target the matching native source/root only.
            val host=SiteControls.key(site.url)
            val existing=db.listEntities().firstOrNull{
                it.kind=="site" && it.source==nativeSource &&
                    SiteControls.key(it.canonicalUrl)==host
            }
            val root=existing?.let{record->
                SourceEntity(record.source,record.entityId,
                    record.displayName,record.canonicalUrl,"site")
            } ?: run {
                val adapter=com.azrael.galleryflow.AdapterRegistry.forSource(
                    nativeSource,a.applicationContext)
                adapter.defaultEntities().firstOrNull{entity->
                    entity.kind=="site" &&
                        SiteControls.key(entity.canonicalUrl)==host
                } ?: SourceEntity(nativeSource,
                    "site:"+host,site.name,site.url,"site")
            }
            if(existing==null){
                db.addEntity(root)
                db.setLiveEnabled(root.source,root.stableId,
                    SiteControls.sync(a,site.url,false))
            }
            root
        }
        val exactUrl=tile.url.substringBefore('#')
        val request=GalleryRequestLedger.Request(SyncMode.BACKFILL,
            entity.stableId,siteHost=SiteControls.key(site.url),
            albumUrl=exactUrl,
            albumTitle=tile.title.take(160),
            siteSource=entity.source.wireName)
        GalleryRequestLedger.remember(a,request)
        a.startForegroundService(Intent(a,SyncForegroundService::class.java)
            .setAction(SyncForegroundService.ACTION_START)
            .putExtra(SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name)
            .putExtra(SyncForegroundService.EXTRA_SITE_ID,entity.stableId)
            .putExtra(SyncForegroundService.EXTRA_SITE_HOST,
                SiteControls.key(site.url))
            .putExtra(SyncForegroundService.EXTRA_SITE_SOURCE,
                entity.source.wireName)
            .putExtra(SyncForegroundService.EXTRA_ALBUM_URL,exactUrl)
            .putExtra(SyncForegroundService.EXTRA_ALBUM_TITLE,request.albumTitle))
        return "Selected gallery queued · "+site.name+
            " · actual files are verified in Downloads"
    }
}
