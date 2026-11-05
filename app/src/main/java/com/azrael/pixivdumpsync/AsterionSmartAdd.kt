package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.SiteLoginActivity
import com.azrael.galleryflow.SyncMode
import com.azrael.galleryflow.SyncForegroundService as GalleryService
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import java.net.URI
import kotlin.concurrent.thread

/** One site intake; route to existing engines rather than opening separate site managers. */
object AsterionSmartAdd {
    private val ui=NocturneDesign
    private enum class Kind(val label:String){
        PIXIV("Pixiv artist"), X("X account"), COSPLAY("Cosplay / image gallery"),
        VIDEO("Video / performer"), COMICS("Manga / comics"),
        BOOKS("EPUB / novels"), TORRENTS("Games / torrents"),
        ASSETS("3D assets / Koikatsu")
    }
    private fun guess(address:String):Kind? {
        val host=runCatching{URI(address).host.orEmpty().lowercase().removePrefix("www.")}
            .getOrDefault("")
        if(host=="pixiv.net"||host.endsWith(".pixiv.net"))return Kind.PIXIV
        if(host=="x.com"||host=="twitter.com"||host=="mobile.twitter.com")return Kind.X
        if(host in listOf("toonily.com","mangaread.org","sushiscan.net","nhentai.net","nhentai.xxx"))
            return Kind.COMICS
        if(host in listOf("koikatsucards.com","db.bepis.moe","gumroad.com"))return Kind.ASSETS
        if(host in listOf("sukebei.nyaa.si","nyaa.si","ggbases.com",
                "onejav.com","f95zone.to"))
            return Kind.TORRENTS
        if(host in listOf("freewebnovel.com","novelbuddy.com","readnovelfull.com",
                "mp4directs.com","shionlib.com","annas-archive.org"))
            return Kind.BOOKS
        // Multi-format creator platforms need a user-selected content module.
        // A creator's profile is not automatically a video source.
        if(host in listOf("patreon.com","fanbox.cc","fantia.jp","onlyfans.com",
                "fansly.com","pawchive.pw","kemono.cr","coomer.st"))return null
        val site=AlohaSources.all.firstOrNull{it.host==host.removePrefix("m.").removePrefix("fr.")}
        return when(site?.area){
            AlohaArea.COSPLAY,AlohaArea.ARTWORK->Kind.COSPLAY
            AlohaArea.VIDEOS,AlohaArea.ADULT,AlohaArea.HENTAI->Kind.VIDEO
            AlohaArea.COMICS->Kind.COMICS
            AlohaArea.BOOKS->Kind.BOOKS
            AlohaArea.GAMES->Kind.TORRENTS
            AlohaArea.ASSETS->Kind.ASSETS
            else->null
        }
    }
    private fun record(a:Activity,kind:Kind,address:String,status:String){
        val p=a.getSharedPreferences("asterion_custom_site_enrollment",0)
        val key=kind.name+"|"+address
        val current=p.getStringSet("sites",emptySet()).orEmpty().toMutableSet()
        current.add(key)
        p.edit().putStringSet("sites",current).putString("status_"+key,status).apply()
        val area=when(kind){
            Kind.PIXIV->"ARTWORK"
            Kind.X->"ARTWORK"
            Kind.COSPLAY->"COSPLAY"
            Kind.VIDEO->"ADULT"
            Kind.COMICS->"COMICS"
            Kind.BOOKS->"BOOKS"
            Kind.TORRENTS->"GAMES"
            Kind.ASSETS->"ASSETS"
        }
        val native=when(kind){
            Kind.PIXIV,Kind.X,Kind.COSPLAY,Kind.VIDEO->true
            Kind.COMICS->status.contains("ZIP queued")
            Kind.BOOKS->address.substringBefore('?').endsWith(".epub",true)
            Kind.TORRENTS->address.startsWith("magnet:",true) ||
                address.substringBefore('?').endsWith(".torrent",true)
            Kind.ASSETS->{
                val host=runCatching{URI(address).host.orEmpty().removePrefix("www.")}.getOrDefault("")
                host=="db.bepis.moe" || host=="koikatsucards.com" ||
                    CreatorArchives.parseCreatorUrl(address)!=null
            }
        }
        SourceIndex.enroll(a.applicationContext,address,area,
            URI(address).host.orEmpty(),native)
    }
    private fun register(a:Activity,url:String,kind:Kind,status:TextView,done:()->Unit){
        val address=url.trim()
        if(!SearchRanking.validUrl(address)){
            status.text="Enter an HTTP(S) profile, collection, or provider URL"
            return
        }
        val app=a.applicationContext
        status.text="Adding to "+kind.label+"…"
        when(kind){
            Kind.PIXIV->{
                val ids=InputParser.parseUserIds(address)
                if(ids.isEmpty()){status.text="A Pixiv artist profile or ID is required";return}
                AppDb(a).use{db->ids.forEach{db.addArtist(it)}}
                record(a,kind,address,"Pixiv artists imported")
                status.text="Added "+ids.size+" Pixiv artists; start their backfill in Pixiv"
                done()
            }
            Kind.X->{
                if(!XPhotoQueue(a).use{it.addSource(address)}){
                    status.text="Invalid or already followed X account";return
                }
                XPhotoScheduler.ensure(a)
                XPhotoEngine.start(a)
                record(a,kind,address,"X photo backfill queued")
                status.text="X account followed · backfill started";done()
            }
            Kind.COSPLAY->thread(name="asterion-enroll-gallery"){
                val result=runCatching{
                    val entity=AdapterRegistry.forUrl(address).resolveEntity(address)
                    GalleryDb(app).use{db->
                        db.addEntity(entity)
                        db.setSelected(entity.source,entity.stableId,true)
                        db.setLiveEnabled(entity.source,entity.stableId,true)
                    }
                    app.startForegroundService(Intent(app,GalleryService::class.java).apply{
                        action=GalleryService.ACTION_START
                        putExtra(GalleryService.EXTRA_MODE,SyncMode.BACKFILL.name)
                        putExtra(GalleryService.EXTRA_SITE_ID,entity.stableId)
                    })
                    entity.displayName
                }
                a.runOnUiThread {
                    result.onSuccess{
                        record(a,kind,address,"Gallery followed; backfill queued")
                        status.text=it+" · following and backfill queued";done()
                    }.onFailure{status.text="Gallery adapter error: "+it.message}
                }
            }
            Kind.VIDEO->{
                val archived=CreatorArchives.parseCreatorUrl(address)
                if(archived!=null){
                    CreatorArchiveStore.set(a,archived,true)
                    CreatorArchiveScheduler.ensure(a)
                    CreatorArchiveScheduler.scanNow(a)
                    record(a,kind,address,"Creator archive follows · backfill queued")
                    status.text="Creator archive added; checking original attachments";done()
                    return
                }
                val saved=VideoDb(a).use{db->
                    val added=db.addWatch(address,Uri.parse(address).host.orEmpty())
                    val w=db.listWatches().firstOrNull{it.address==address}
                    if(w!=null){db.enableWatch(w.id,true);db.resetWatchBackfill(w.id)}
                    added||w!=null
                }
                if(saved){
                    VideoEngine.launch(a,true)
                    record(a,kind,address,"Video monitoring and backfill queued")
                    status.text="Video source enabled; historical scan requested";done()
                } else status.text="Video site cannot be enrolled"
            }
            Kind.COMICS->{
                val domain=Uri.parse(address).host.orEmpty().removePrefix("www.")
                if(domain in setOf("nhentai.net","nhentai.xxx") &&
                    !NhentaiChapters.isGallery(address)){
                    status.text="Select an nHentai gallery /g/ID. Catalog listings cannot form one complete ZIP"
                    return
                }
                val path=Uri.parse(address).path.orEmpty().trim('/')
                if(path.isNotEmpty() && path !in listOf("","manga","serie","series","home")){
                    val title=path.substringAfterLast('/').ifBlank{"Series"}
                    ChapterQueue(a).use{it.enqueue(address,title)}
                    ChapterArchives.start(a)
                    record(a,kind,address,"Comic series ZIP queued")
                    status.text="Comic series added to chapter ZIP queue";done()
                }else{
                    record(a,kind,address,"Catalogued · a series URL is needed for backfill")
                    status.text="Saved as Comics. Provide a series URL to start a ZIP archive";done()
                }
            }
            Kind.BOOKS->{
                if(address.substringBefore('?').endsWith(".epub",true)){
                    ResourceDownloads.route(a,address,"Books")
                    record(a,kind,address,"EPUB download requested")
                    status.text="EPUB download queued";done()
                }else if(NovelEpubParser.supportedSeries(address)){
                    val series=Uri.parse(address).path.orEmpty().trim('/')
                        .substringAfterLast('/').replace('-', ' ').ifBlank{"Novel"}
                    NovelEpubService.enqueue(a,address,series)
                    val zipped=Uri.parse(address).host.orEmpty()
                        .removePrefix("www.")=="freewebnovel.com"
                    record(a,kind,address,if(zipped)
                        "Complete novel readable ZIP queued" else
                        "Complete novel EPUB extraction queued")
                    status.text=if(zipped)
                        "Entire novel queued as one readable ZIP (HTML chapters + EPUB)"
                        else "Entire novel queued for EPUB assembly · historical chapters included"
                    done()
                }else{
                    record(a,kind,address,"Provider indexed · EPUB original required")
                    status.text="Source indexed; only verified EPUB files or supported complete novel series are acquired"
                    done()
                }
            }
            Kind.TORRENTS->{
                if(address.startsWith("magnet:") || address.endsWith(".torrent",true)){
                    val categories=arrayOf("Games","Videos","Books")
                    AlertDialog.Builder(a).setTitle("Torrent content type")
                        .setItems(arrayOf("Game files","Adult / other videos","EPUB books")){_,which->
                            ResourceDownloads.route(a,address,categories[which])
                            record(a,kind,address,"Torrent metadata requested · "+categories[which])
                            status.text="Torrent file selection requested"
                            done()
                        }.setNegativeButton("Cancel",null).show()
                }else{
                    status.text="Index saved · dedicated search adapter pending"
                    record(a,kind,address,status.text.toString())
                    done()
                }
            }
            Kind.ASSETS->{
                val archive=CreatorArchives.parseCreatorUrl(address)
                if(archive!=null){
                    CreatorArchiveStore.set(a,archive,true)
                    CreatorArchiveScheduler.ensure(a)
                    CreatorArchiveScheduler.scanNow(a)
                    record(a,kind,address,"Creator archive followed · attachment backfill queued")
                    status.text="Creator archive added to 3D assets";done()
                }else{
                    record(a,kind,address,"Saved under 3D assets · indexing supported originals")
                    status.text="3D assets source enrolled; previews will appear in Assets";done()
                }
            }
        }
    }
    /** Follow an explicit cross-platform creator profile using the same native
     * enrollment paths as Add Site. No new, parallel downloader is introduced.
     */
    fun followLinkedProfile(a:Activity,url:String,onDone:(String)->Unit){
        val parsed=CrossPlatformProfileParser.normalized(url)
        if(parsed==null){
            onDone("Not a supported creator-profile URL");return
        }
        val kind=guess(parsed.first)
        if(kind!=null){
            val status=TextView(a)
            register(a,parsed.first,kind,status){onDone(status.text.toString())}
            return
        }
        val archive=CreatorArchives.parseCreatorUrl(parsed.first)
        if(archive!=null){
            CreatorArchiveStore.set(a,archive,true)
            CreatorArchiveScheduler.ensure(a)
            CreatorArchiveScheduler.scanNow(a)
            onDone("Creator archive enrolled for backfill")
            return
        }
        // Creator platforms without a native API use the existing scoped
        // SourceIndex fallback and the account's already authorized session.
        SourceIndex.enroll(a.applicationContext,parsed.first,"CREATORS",
            Uri.parse(parsed.first).host.orEmpty(),false)
        CreatorNames.remember(a,parsed.first.substringAfterLast('/'),"CREATORS",true)
        onDone("Creator profile enrolled; originals and access checked by SourceIndex")
    }
    fun render(a:Activity,p:LinearLayout,done:()->Unit){
        p.addView(ui.text(a,"Add a site",23f,ui.white,true),ui.lp(a,14))
        p.addView(ui.text(a,"Paste a creator, collection or source URL. Known sites are categorized automatically.",
            12f,ui.secondary),ui.lp(a,5))
        val box=ui.card(a)
        val field=EditText(a).apply{
            hint="https://…";setSingleLine(true);textSize=16f
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        box.addView(field,ui.lp(a,0))
        val status=ui.text(a,"",12f,ui.secondary)
        box.addView(ui.action(a,"ADD & BACKFILL",true){
            val url=field.text.toString().trim()
            if(url.startsWith("magnet:?xt=urn:btih:",true)){
                AlertDialog.Builder(a).setTitle("Select torrent content type")
                    .setItems(arrayOf("Game / visual novel","Video","EPUB book")){_,type->
                        val folder=arrayOf("Games","Videos","Books")[type]
                        ResourceDownloads.route(a,url,folder)
                        status.text="Torrent queued under "+folder+" · file selection"
                        done()
                    }.setNegativeButton("Cancel",null).show()
                return@action
            }
            val proposed=guess(url)
            if(proposed!=null)register(a,url,proposed,status,done)
            else if(SearchRanking.validUrl(url)){
                AlertDialog.Builder(a).setTitle("Choose content type")
                    .setItems(Kind.values().map{it.label}.toTypedArray()){_,which->
                        register(a,url,Kind.values()[which],status,done)
                    }.setNegativeButton("Cancel",null).show()
            }else status.text="Enter a valid site or profile URL"
        },ui.lp(a,8))
        // Unknown providers never need a fake site-specific adapter just to
        // browse their pages or download an original with the user's session.
        box.addView(ui.action(a,"BROWSE SITE · IN APP",false){
            val url=field.text.toString().trim()
            if(!SearchRanking.validUrl(url)){
                status.text="Enter a valid HTTPS site URL"
                return@action
            }
            a.startActivity(Intent(a,com.azrael.galleryflow.ProviderWebActivity::class.java)
                .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,url)
                .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_FOLDER,"Files"))
        },ui.lp(a,8))
        box.addView(ui.action(a,"DOWNLOAD DIRECT FILE / TORRENT",false){
            val url=field.text.toString().trim()
            if(!url.startsWith("magnet:?xt=urn:btih:",true) &&
                !SearchRanking.validUrl(url)){
                status.text="Enter an HTTPS file or magnet link"
                return@action
            }
            ResourceDownloads.route(a,url,"Files")
            status.text="Sent to the unified download queue; provider access is still required"
        },ui.lp(a,6))
        box.addView(status,ui.lp(a,8))
        p.addView(box,ui.lp(a,11))
        val records=a.getSharedPreferences("asterion_custom_site_enrollment",0)
            .getStringSet("sites",emptySet()).orEmpty()
        if(records.isNotEmpty())
            p.addView(ui.text(a,records.size.toString()+" added sources are retained in their modules",
                12f,ui.secondary),ui.lp(a,11))
    }
}
