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
        if(host in listOf("sukebei.nyaa.si","nyaa.si","ggbases.com","www.ggbases.com"))
            return Kind.TORRENTS
        if(host in listOf("freewebnovel.com","novelbuddy.com","readnovelfull.com","mp4directs.com"))
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
                host=="db.bepis.moe" || host=="koikatsucards.com"
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
                }else{
                    record(a,kind,address,"Catalogued · provider-specific EPUB sync pending")
                    status.text="Saved under EPUB. Automatic novel extraction is not yet supported for this provider";done()
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
                record(a,kind,address,"Saved under 3D assets · native provider adapter required")
                status.text="Saved under 3D assets; open Assets to search for cards and models";done()
            }
        }
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
            val proposed=guess(url)
            if(proposed!=null)register(a,url,proposed,status,done)
            else if(SearchRanking.validUrl(url)){
                AlertDialog.Builder(a).setTitle("Choose content type")
                    .setItems(Kind.values().map{it.label}.toTypedArray()){_,which->
                        register(a,url,Kind.values()[which],status,done)
                    }.setNegativeButton("Cancel",null).show()
            }else status.text="Enter a valid site or profile URL"
        },ui.lp(a,8))
        box.addView(status,ui.lp(a,8))
        p.addView(box,ui.lp(a,11))
        val records=a.getSharedPreferences("asterion_custom_site_enrollment",0)
            .getStringSet("sites",emptySet()).orEmpty()
        if(records.isNotEmpty())
            p.addView(ui.text(a,records.size.toString()+" added sources are retained in their modules",
                12f,ui.secondary),ui.lp(a,11))
    }
}
