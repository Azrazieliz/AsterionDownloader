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

/** All 76 unique links in the four identical Aloha exports, classified into existing panels.
 * Non-download reference sites remain references. An account-specific calendar query was
 * excluded from the repository to avoid leaking user-specific appointment parameters.
 */
enum class AlohaArea { ARTWORK, COSPLAY, VIDEOS, ADULT, HENTAI, COMICS, BOOKS, GAMES, ASSETS, REFERENCE, STUDY }
data class AlohaSite(val name:String,val url:String,val area:AlohaArea){
    val host:String get()=runCatching{java.net.URI(url).host.orEmpty().removePrefix("www.")}.getOrDefault("")
}
object AlohaSources {
    private val sourceRows="""Ugoira converter|https://ugoira.com/|ARTWORK
CloudConvert|https://cloudconvert.com/|ASSETS
YouTube downloader|https://www.socialplug.io/free-tools/youtube-video-downloader|VIDEOS
Reversely.ai|https://www.reversely.ai/|ARTWORK
TinEye|https://tineye.com/|ARTWORK
Cleithral|https://www.cleithral.com/|ARTWORK
Dezgo|https://dezgo.com/txt2img|ARTWORK
Civitai|https://civitai.com/|ASSETS
Pic Upscaler|https://imgupscaler.com/|ARTWORK
Shoujo Kaisen database|https://game8-jp.translate.goog/shoujokaisen?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en|REFERENCE
Houchi Shoujo database|https://game8-jp.translate.goog/houchishoujo?_x_tr_sl=auto&_x_tr_tl=en&_x_tr_hl=en|REFERENCE
Saint Seiya LoJ|https://ssloj.com/characters|REFERENCE
Reverse 1999 characters|https://www.prydwen.gg/re1999/characters|REFERENCE
Azur Lane database|https://azurlane.mrlar.dev/db/|REFERENCE
Fate Grand Order servants|https://grandorder.gamepress.gg/c/servants|REFERENCE
Honkai Impact 3 wiki|https://honkaiimpact3.fandom.com/wiki/Honkai_Impact_3_Wiki|REFERENCE
Dokkan wiki|https://dbz-dokkanbattle.fandom.com/wiki/Dragon_Ball_Z_Dokkan_Battle_Wiki|REFERENCE
One Piece Treasure Cruise DB|https://optc-db.github.io/|REFERENCE
Wuthering Waves|https://wuthering.gg/|REFERENCE
Bleach Brave Souls|https://bbs-simulator.com/characters|REFERENCE
Counter Side database|https://www.prydwen.gg/counter-side/database/|REFERENCE
Arknights operators|https://nullwish.info/operators|REFERENCE
4KHD|https://www.4khd.com/|COSPLAY
Buondua|https://buondua.com/|COSPLAY
CosplayTele|https://cosplaytele.com/|COSPLAY
Kiutaku|https://kiutaku.com/|COSPLAY
Pixiv|https://www.pixiv.net/en/|ARTWORK
Rule34 images|https://rule34.xxx|ARTWORK
Anime-Sama|https://anime-sama.pw|VIDEOS
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
Toonily|https://toonily.com/|COMICS
MangaRead|https://www.mangaread.org/|COMICS
Sushi-Scan|https://sushiscan.net/|COMICS
MangaDex|https://mangadex.org/|COMICS
Freeweb Novel|https://freewebnovel.com/|BOOKS
NovelBuddy|https://novelbuddy.com/home|BOOKS
ReadNovelFull|https://readnovelfull.com/|BOOKS
Fate Stay Night VN|https://fatestaynight.vnovel.org/|GAMES
NHentai mirror|https://nhentai.xxx/language/english/|COMICS
Sukebei Nyaa|https://sukebei.nyaa.si/|GAMES
MP4Directs light novels|https://mp4directs.com/|BOOKS
Nyaa|https://nyaa.si/|GAMES
NHentai|https://nhentai.net/language/english/|COMICS
Kaisen Wiki|https://kaisen-wiki.h0rny.net/|REFERENCE
Nyaa mirror|https://nyaa.v300.eu.org/|GAMES
UPC study calendar|https://upc.medisupconnect.com/|STUDY
BepisDB Koikatsu|https://db.bepis.moe/koikatsu|ASSETS""".trimIndent()
    val all:List<AlohaSite> by lazy{
        sourceRows.lines().filter{it.isNotBlank()}.map{line->
            val parts=line.split('|')
            require(parts.size==3){"Broken Aloha catalog row"}
            AlohaSite(parts[0],parts[1],AlohaArea.valueOf(parts[2]))
        }.also{sites->
            require(sites.size==76 && sites.distinctBy{it.url}.size==76){"Incomplete Aloha export"}
            require(sites.none{it.url.contains("slot-id=")}){"Do not publish personal calendar URL"}
        }
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
            AlohaArea.ADULT,AlohaArea.HENTAI->"Videos"
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
            AlohaArea.VIDEOS,AlohaArea.ADULT,AlohaArea.HENTAI->{
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
                status.text="Use a series URL in Comics to queue chapter ZIPs on supported hosts"
            }
            AlohaArea.BOOKS,AlohaArea.GAMES->{
                browse(a,site)
                status.text="Open a title or accessible original file; torrent transfers use Games/Books"
            }
            AlohaArea.REFERENCE,AlohaArea.STUDY->{
                browse(a,site)
                status.text="Reference opened; this is not a download provider"
            }
        }
    }

    fun render(a:Activity,parent:LinearLayout,areas:List<AlohaArea>,heading:String){
        val matches=all.filter{it.area in areas}.distinctBy{it.url}
        if(matches.isEmpty())return
        val ui=NocturneDesign
        val frame=ui.card(a)
        frame.addView(ui.text(a,heading+" · ALOHA SOURCES ("+matches.size+")",
            17f,ui.white,true),ui.lp(a,5))
        frame.addView(ui.text(a,
            "All exported sites are attached to their relevant existing systems. "+
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
