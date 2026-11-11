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

/** User-approved provider subset (Pornhits and duplicate OMG retired) from the four identical Aloha exports.
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
Pornhub|https://www.pornhub.com/|ADULT
EPORNER|https://www.eporner.com/|ADULT
XVIDEOS|https://fr.xvideos.com/|ADULT
HQPornerxxx|https://hqpornerxxx.com/|ADULT
Jav Guru|https://jav.guru/|ADULT
Roshy JAV|https://roshy.tv/category/decensored-6/|ADULT
JAVIDEO|https://javideo.net/en/|ADULT
CUMS|https://cums.net/|ADULT
PeekVids|https://www.peekvids.com/|ADULT
PornTrex|https://www.porntrex.com/|ADULT
TNAFLIX|https://www.tnaflix.com/|ADULT
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
Freeweb Novel|https://freewebnovel.com/home|BOOKS
NovelBuddy|https://novelbuddy.me/home|BOOKS
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
            require(sites.size==41 && sites.distinctBy{it.url}.size==41){"Incorrect approved Aloha source set"}
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

    /** All approved provider entries feed one preview-first native surface.
     * No site spinner, provider-by-provider list UI, or browser redirection.
     * Source/login maintenance remains available through its dedicated dialog.
     * Original native Kuroha/Kyora/VideoFlow paths stay unchanged.
     */
    fun render(a:Activity,parent:LinearLayout,areas:List<AlohaArea>,heading:String){
        val scope=areas.filter{it !in setOf(AlohaArea.REFERENCE,AlohaArea.STUDY)}
            .map{it.name}.distinct()
        if(scope.isEmpty())return
        SourceIndexPanel.render(a,parent,scope,heading)
    }

}
