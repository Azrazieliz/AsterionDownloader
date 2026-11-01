package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import com.azrael.galleryflow.AdapterRegistry
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.ProviderWebActivity
import com.azrael.galleryflow.SyncForegroundService
import com.azrael.galleryflow.SyncMode
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import kotlin.concurrent.thread

/** User-approved 43 URL subset from the four identical Aloha exports.
 * Previously supported sources remain in their original native engines. Rejected
 * personal, utilities, reference and unrelated bookmarks are not registered.
 */
enum class AlohaArea { ARTWORK, COSPLAY, VIDEOS, ADULT, HENTAI, COMICS, BOOKS, GAMES, ASSETS, REFERENCE, STUDY }
data class AlohaSite(val name:String,val url:String,val area:AlohaArea){
    val host:String get()=runCatching{java.net.URI(url).host.orEmpty().removePrefix("www.")}.getOrDefault("")
}
object AlohaSources {
    private val sourceRows="""4KHD|https://www.4khd.com/|COSPLAY
Buondua|https://buondua.com/|COSPLAY
CosplayTele|https://cosplaytele.com/|COSPLAY
Kiutaku|https://kiutaku.com/|COSPLAY
Porn Pics|https://www.pornpics.com/|COSPLAY
Archivebate|https://archivebate.com/|COSPLAY
NotFans|https://notfans.com/|COSPLAY
CamStreams|https://camstreams.tv/|ADULT
Celeb.st|https://celeb.st/feed/hot|COSPLAY
FreePornVideos|https://www.freepornvideos.xxx/|ADULT
WatchPorn|https://watchporn.to/|ADULT
Pornhub|https://pornhub.com/|ADULT
EPORNER|https://www.eporner.com/|ADULT
XVIDEOS|https://fr.xvideos.com/|ADULT
HQPornerxxx|https://hqpornerxxx.com/|ADULT
OMG.xxx|https://www.omg.xxx/fr/|ADULT
Jav Guru|https://jav.guru/|ADULT
Roshy JAV|https://roshy.tv/category/decensored/|ADULT
JAVIDEO|https://javideo.net/en/|ADULT
CUMS|https://cums.net/|ADULT
PeekVids|https://www.peekvids.com/|ADULT
PornTrex|https://www.porntrex.com/|ADULT
TNAFLIX|https://m.tnaflix.com/|ADULT
Pornhits|https://www.pornhits.com/main/|ADULT
XNXX|https://www.xnxx.com/|ADULT
XXXClub|https://xxxclub.to/|ADULT
OneJAV|https://onejav.com/|ADULT
MP4Hentai|https://mp4hentai.com/category/upcoming-hentai-release/|HENTAI
Hentai Bros|https://hentaibros.net/|HENTAI
OnlyHentai|https://www.onlyhentaistuff.com/|HENTAI
Uncensored Hentai|https://uncensoredhentai.xxx/|HENTAI
3DHentai|https://3dhentaix.com/|HENTAI
Hentai Haven|https://hentaihaven.xxx/|HENTAI
Rule34 videos|https://rule34video.com/|HENTAI
Sushi-Scan|https://sushiscan.net/|COMICS
Freeweb Novel|https://freewebnovel.com/|BOOKS
NovelBuddy|https://novelbuddy.com/home|BOOKS
ReadNovelFull|https://readnovelfull.com/|BOOKS
NHentai mirror|https://nhentai.xxx/language/english/|COMICS
Sukebei Nyaa|https://sukebei.nyaa.si/|GAMES
MP4Directs light novels|https://mp4directs.com/|BOOKS
NHentai|https://nhentai.net/language/english/|COMICS
BepisDB Koikatsu|https://db.bepis.moe/koikatsu|ASSETS""".trimIndent()
    val all:List<AlohaSite> by lazy{
        sourceRows.lines().filter{it.isNotBlank()}.map{line->
            val parts=line.split('|')
            require(parts.size==3){"Broken Aloha catalog row"}
            AlohaSite(parts[0],parts[1],AlohaArea.valueOf(parts[2]))
        }.also{sites->
            require(sites.size==43 && sites.distinctBy{it.url}.size==43){"Incorrect approved Aloha source set"}
            require(sites.none{it.url.contains("slot-id=")}){"Do not publish personal calendar URL"}
        }
    }
    // Native Pixiv/Kyora/VideoFlow/Koikatsu/torrent sources are not duplicated as
    // supplemental bookmark rows. Their original engines and saved data stay intact.
    internal val alreadyIntegratedHosts=setOf("4khd.com","buondua.com","cosplaytele.com",
        "kiutaku.com","watchporn.to","pornhub.com","eporner.com","xvideos.com",
        "porntrex.com","peekvids.com","tnaflix.com","xnxx.com",
        "hentaihaven.xxx","sukebei.nyaa.si","db.bepis.moe")
    val additions:List<AlohaSite> get()=all.filterNot{
        val siteHost=it.host.removePrefix("m.").removePrefix("fr.")
        siteHost in alreadyIntegratedHosts
    }
    fun by(vararg areas:AlohaArea):List<AlohaSite> =
        all.filter{it.area in areas}.distinctBy{it.url}

    fun browse(a:Activity,site:AlohaSite){
        if(site.area==AlohaArea.ASSETS && site.host=="gumroad.com"){
            a.startActivity(Intent(a,AssetMarketplaceActivity::class.java)
                .putExtra(AssetMarketplaceActivity.EXTRA_URL,site.url))
            return
        }
        val destination=when(site.area){
            AlohaArea.ARTWORK->"Artwork"
            AlohaArea.COSPLAY->"Cosplay"
            AlohaArea.VIDEOS->"Videos"
            AlohaArea.ADULT,AlohaArea.HENTAI->if(site.host=="onejav.com")"Adult" else "Videos"
            AlohaArea.COMICS->"Comics"
            AlohaArea.BOOKS->"Books"
            AlohaArea.GAMES->"Games"
            AlohaArea.ASSETS->"Assets"
            AlohaArea.REFERENCE,AlohaArea.STUDY->"References"
        }
        a.startActivity(Intent(a,ProviderWebActivity::class.java)
            .putExtra(ProviderWebActivity.EXTRA_URL,site.url)
            .putExtra(ProviderWebActivity.EXTRA_FOLDER,destination))
    }

    private fun monitor(a:Activity,site:AlohaSite,status:TextView){
        when(site.area){
            AlohaArea.COSPLAY,AlohaArea.ARTWORK->{
                // Pixiv is an account-specific native engine, not a generic GalleryFlow site.
                if(site.host=="pixiv.net"){browse(a,site);return}
                status.text="Connecting "+site.name+" to existing Kyora source adapters…"
                thread(name="aloha-follow-gallery"){
                    val result=runCatching{
                        val entity=AdapterRegistry.forUrl(site.url).resolveEntity(site.url)
                        GalleryDb(a).use{it.addEntity(entity)}
                        entity
                    }
                    a.runOnUiThread{
                        result.onSuccess{entity->
                            a.startForegroundService(Intent(a,SyncForegroundService::class.java)
                                .setAction(SyncForegroundService.ACTION_START)
                                .putExtra(SyncForegroundService.EXTRA_MODE,SyncMode.BACKFILL.name)
                                .putExtra(SyncForegroundService.EXTRA_SITE_ID,entity.stableId))
                            status.text=site.name+" enrolled in Kyora backfill. Review transfer errors in Kyora."
                        }.onFailure{status.text="Site could not be indexed: "+it.message}
                    }
                }
            }
            AlohaArea.ADULT->if(site.host=="onejav.com"){
                browse(a,site)
                status.text="OneJAV is a torrent source: select original torrent/magnet files. VideoFlow cannot treat its listing as MP4."
            }else{
                val added=VideoDb(a).use{it.addWatch(site.url,site.name)}
                VideoEngine.launch(a,true)
                status.text=if(added)site.name+" added to VideoFlow source watches; scan queued"
                    else site.name+" already watched; scan requested"
            }
            AlohaArea.VIDEOS,AlohaArea.HENTAI->{
                val added=VideoDb(a).use{it.addWatch(site.url,site.name)}
                VideoEngine.launch(a,true)
                status.text=if(added)site.name+" added to VideoFlow source watches; scan queued"
                    else site.name+" already watched; scan requested"
            }
            AlohaArea.ASSETS->{
                if(site.host=="db.bepis.moe"){
                    a.startActivity(Intent(a,KoikatsuCardActivity::class.java))
                    status.text="Native Koikatsu card search opened"
                }else{browse(a,site);status.text="Browse and select any accessible original asset"}
            }
            AlohaArea.COMICS->{
                browse(a,site)
                status.text="Chapter ZIP download for this site requires a verified adapter; existing Toonily/MangaRead downloads are preserved."
            }
            AlohaArea.BOOKS,AlohaArea.GAMES->{
                browse(a,site)
                status.text="Browse source; native EPUB/game transfer requires a directly resolved original URL."
            }
            AlohaArea.REFERENCE,AlohaArea.STUDY->{
                browse(a,site)
                status.text="Reference opened; this is not a download provider"
            }
        }
    }

    fun render(a:Activity,parent:LinearLayout,areas:List<AlohaArea>,heading:String){
        val matches=additions.filter{it.area in areas}.distinctBy{it.url}
        if(matches.isEmpty())return
        val ui=NocturneDesign
        val frame=ui.card(a)
        frame.addView(ui.text(a,heading+" · ALOHA SOURCES ("+matches.size+")",
            17f,ui.white,true),ui.lp(a,5))
        frame.addView(ui.text(a,
            "Only approved Aloha additions are listed here; sources already integrated "+
            "into the app remain in their existing native modules. "+
            "Native sync/download is offered where supported; other sources open with a "+
            "browser download listener for files already accessible to you.",
            12f,ui.secondary),ui.lp(a,8))
        val picker=Spinner(a)
        picker.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,
            matches.map{it.name+" · "+it.host})
        frame.addView(picker,ui.lp(a,7))
        val selection=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val status=ui.text(a,"Select a bookmarked source",12f,ui.secondary)
        fun display(site:AlohaSite){
            selection.removeAllViews()
            val thumbnail=ImageView(a).apply{
                scaleType=ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(ui.raised)
            }
            selection.addView(thumbnail,LinearLayout.LayoutParams(-1,ui.px(a,128)))
            CreatorPreviews.sourceThumbnail(a,thumbnail,site.url)
            selection.addView(ui.text(a,site.name,15f,ui.white,true),ui.lp(a,5))
            selection.addView(ui.text(a,site.url,11f,ui.secondary),ui.lp(a,4))
            val nativeSync=site.area in listOf(AlohaArea.COSPLAY,AlohaArea.ADULT,AlohaArea.HENTAI) &&
                site.host!="onejav.com"
            val info=when {
                nativeSync && AlohaDefaults.seeded(a,site)->
                    "Enabled in existing sync/backfill queue. Download coverage varies by source."
                nativeSync -> AlohaDefaults.error(a,site)?.let{
                    "Source enrollment failed; retry on next app start: "+it.take(150)
                } ?: "Enrollment into existing sync engine is pending."
                site.host=="onejav.com" ->
                    "Torrent index: magnet or .torrent links use native file selection."
                site.area==AlohaArea.BOOKS ->
                    "EPUB-only source. A dedicated chapter-to-EPUB adapter is not yet verified."
                site.area==AlohaArea.COMICS ->
                    "Dedicated chapter ZIP adapter not yet verified for this site."
                else -> "Original-file downloads and individual source capabilities vary."
            }
            selection.addView(ui.text(a,info,11f,ui.secondary),ui.lp(a,7))
            val isReference=site.area in listOf(AlohaArea.REFERENCE,AlohaArea.STUDY)
            val action=when(site.area){
                AlohaArea.COSPLAY,AlohaArea.ARTWORK->"FOLLOW / BACKFILL WITH EXISTING IMAGE ENGINE"
                AlohaArea.VIDEOS,AlohaArea.ADULT,AlohaArea.HENTAI->
                    "MONITOR SITE IN VIDEOFLOW"
                AlohaArea.ASSETS->"OPEN ASSETS / ORIGINALS"
                AlohaArea.COMICS->"BROWSE COMICS"
                AlohaArea.BOOKS,AlohaArea.GAMES->"OPEN SOURCE / DOWNLOAD FILES"
                else->"OPEN REFERENCE"
            }
            if(!isReference)
                selection.addView(ui.action(a,action,true){monitor(a,site,status)},ui.lp(a,9))
            selection.addView(ui.action(a,if(isReference)"OPEN REFERENCE"
                else "BROWSE SOURCE / DOWNLOAD EXPOSED FILE",false){
                browse(a,site)
                status.text="Opened "+site.name
            },ui.lp(a,7))
        }
        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,
                view:android.view.View?,position:Int,id:Long){display(matches[position])}
        }
        frame.addView(selection,ui.lp(a,8))
        frame.addView(status,ui.lp(a,8))
        parent.addView(frame,ui.lp(a,13))
    }
}
