package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*
import com.azrael.galleryflow.GalleryDb
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import java.text.Normalizer
import java.util.Locale
import kotlin.concurrent.thread

/** Local, explainable recommendations across every indexed module and saved video query. */
object AsterionContentSuggestions {
    private val ui=NocturneDesign
    private val areas=listOf("ALL","ARTWORK","VIDEOS","X","COSPLAY","CREATORS",
        "KOIKATSU","BOOKS","COMICS","GAMES","ASSETS")
    data class Pick(val title:String,val url:String,val preview:String?,val area:String,
        val kind:String,val source:String,val reason:String,val relevance:Int)
    private val noise=setOf("the","and","for","with","from","video","videos",
        "games","game","artist","model","comics","book","books","adult","all","pixiv")
    internal fun suggestionArea(sourceArea:String):String=when(sourceArea){
        "ADULT","HENTAI","VIDEOS"->"VIDEOS"
        else->sourceArea
    }
    private fun words(raw:String):Set<String> =
        Normalizer.normalize(raw,Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter{it.length>=3 && it !in noise}.toSet()
    internal fun relevance(interests:List<String>,title:String):Pair<Int,String>{
        val target=words(title)
        if(target.isEmpty())return 0 to ""
        val matches=interests.asSequence().flatMap{words(it).asSequence()}
            .distinct().filter{it in target}.toList()
        if(matches.isEmpty())return 0 to ""
        return (matches.size*14+if(matches.size>1)8 else 0) to
            "Related: "+matches.take(3).joinToString(", ")
    }
    private fun following(a:Activity):List<String>{
        val user=a.getSharedPreferences("asterion_recommendations",0)
            .getString("interests","").orEmpty().split('\n',',',';')
        val creators=NameWatchDb(a).use{it.list().map{x->x.name}}
        val gallery=GalleryDb(a).use{it.listEntities().map{x->x.displayName}}
        val pixiv=AppDb(a).use{it.listArtists().mapNotNull{x->x.label}}
        val chosen=CreatorNameDb(a).use{it.chosen(null,150)}
        val x=XPhotoQueue(a).use{it.allSources().filter{source->source.enabled}
            .map{source->source.handle}}
        val koi=KoikatsuFollowDb(a).use{it.follows().filter{follow->follow.enabled}
            .map{follow->follow.name}}
        return (user+chosen+creators+gallery+pixiv+x+koi)
            .map{it.trim().removePrefix("@")}.filter{it.length>=3}
            .distinct().take(240)
    }
    fun picks(a:Activity,mode:String="ALL"):List<Pick>{
        val terms=following(a)
        if(terms.isEmpty())return emptyList()
        val stored=SourceIndex.items(a,null,250)
        val sources=stored.mapNotNull{x->
            val area=suggestionArea(x.area)
            if(mode!="ALL"&&area!=mode)return@mapNotNull null
            val (score,reason)=relevance(terms,x.title)
            if(score<=0)return@mapNotNull null
            Pick(x.title,x.url,x.preview,area,x.kind,x.source,reason,score)
        }
        val saved=if(mode=="ALL"||mode=="VIDEOS")
            SearchResultsDb(a).use{it.list(250)}.asSequence().filter{!it.queued}
                .mapNotNull{x->
                    val (score,reason)=relevance(terms,x.title+" "+x.query)
                    if(score<=0)return@mapNotNull null
                    Pick(x.title,x.url,x.thumbnail,"VIDEOS","video",x.source,reason,
                        score+x.score.coerceIn(0,20))
                }.toList()
            else emptyList()
        val native=ArrayList<Pick>()
        if(mode in setOf("ALL","X")){
            val photos=XPhotoQueue(a).use{it.recent(260)}
            val handles=XPhotoQueue(a).use{it.allSources().map{v->
                v.handle.lowercase() to v
            }.toMap()}
            for(photo in photos.take(180)){
                val handle=runCatching{Uri.parse(photo.source).pathSegments
                    .firstOrNull().orEmpty().lowercase()}.getOrDefault("")
                if(handle !in handles)continue
                val (score,reason)=relevance(terms,handle)
                if(score==0)continue
                native+=Pick("@"+handle+" · latest X photo",photo.photo,photo.photo,
                    "X","xphoto",photo.source,reason,score+12)
            }
        }
        if(mode in setOf("ALL","COSPLAY")){
            val entityMap=GalleryDb(a).use{it.listEntities()
                .associateBy{entity->entity.displayName}}
            val gallery=GalleryDb(a).use{it.latestSelectedGalleries(90)}
            for((creator,item) in gallery){
                val origin=entityMap[creator]?.canonicalUrl?:continue
                val image=item.firstUrl?.takeIf{SearchRanking.validUrl(it)}?:continue
                val (score,reason)=relevance(terms,creator+" "+item.title)
                if(score==0)continue
                native+=Pick(creator+" · "+item.title,origin,image,"COSPLAY",
                    "gallery",origin,reason,score+8)
            }
        }
        if(mode in setOf("ALL","KOIKATSU")){
            KoikatsuFollowDb(a).use{db->
                for(follow in db.follows().filter{it.enabled}.take(60)){
                    for(card in db.cards(follow.name,12)){
                        val (score,reason)=relevance(terms,follow.name+" "+card.name+
                            " "+card.author)
                        if(score==0)continue
                        val preview=card.preview?:card.download
                            .takeIf{card.source=="BepisDB"}
                        native+=Pick(card.name,card.page,preview,"KOIKATSU",
                            "koikatsu",card.source,reason,score+10)
                    }
                }
            }
        }
        val dedup=(sources+saved+native).distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedWith(compareByDescending<Pick>{it.relevance}
                .thenByDescending{it.preview!=null}.thenBy{it.title})
        // Diversify sources: no single source may overwhelm the recommendations.
        val seen=HashMap<String,Int>()
        return dedup.filter{item->
            val host=runCatching{Uri.parse(item.source).host.orEmpty()}
                .getOrDefault("").ifBlank{item.source}
            val key=host+"|"+item.area
            val n=seen.getOrDefault(key,0)
            if(n>=8)false else {seen[key]=n+1;true}
        }.take(60)
    }
    fun render(a:Activity,parent:LinearLayout,refresh:()->Unit){
        val pref=a.getSharedPreferences("asterion_recommendations",0)
        val selected=pref.getString("content_filter","ALL").orEmpty().takeIf{it in areas}?:"ALL"
        val section=ui.card(a)
        section.addView(ui.text(a,"Recommended content",18f,ui.white,true))
        section.addView(ui.text(a,
            "Matches from your follows and locally indexed titles. No invented results.",
            12f,ui.secondary),ui.lp(a,4))
        val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        for(area in areas){
            row.addView(ui.chip(a,area,area==selected){
                pref.edit().putString("content_filter",area).apply()
                refresh()
            },LinearLayout.LayoutParams(-2,-2).apply{rightMargin=ui.px(a,7)})
        }
        section.addView(HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false;addView(row)
        },ui.lp(a,10))
        val items=picks(a,selected)
        if(items.isEmpty()){
            section.addView(ui.text(a,"No high-confidence matches yet. Add interests above and "+
                "let source syncing index real previews.",12f,ui.secondary),ui.lp(a,10))
        }else{
            val track=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            for(item in items){
                val tile=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,7),ui.px(a,7),ui.px(a,7),ui.px(a,7))
                    background=ui.shape(a,ui.raised,12)
                    setOnClickListener{show(a,item)}
                }
                val cover=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.surface)
                    contentDescription=item.title+" · "+item.area
                }
                tile.addView(cover,LinearLayout.LayoutParams(-1,ui.px(a,130)))
                if(item.preview!=null)
                    RemoteImageLoader.load(a,cover,item.preview,item.source)
                tile.addView(ui.text(a,item.title.take(50),12f,ui.white,true),ui.lp(a,7))
                tile.addView(ui.text(a,item.reason,11f,ui.secondary),ui.lp(a,4))
                track.addView(tile,LinearLayout.LayoutParams(ui.px(a,156),-2)
                    .apply{rightMargin=ui.px(a,8)})
            }
            section.addView(HorizontalScrollView(a).apply{
                isHorizontalScrollBarEnabled=false;addView(track)
            },ui.lp(a,11))
        }
        parent.addView(section,ui.lp(a,11))
    }
    private fun show(a:Activity,item:Pick){
        AlertDialog.Builder(a).setTitle(item.title)
            .setMessage(item.reason+"\n"+item.area+" · "+item.kind)
            .setItems(arrayOf("Download accessible original","Open item page")){_,which->
                if(which==1){
                    a.startActivity(Intent(a,
                        com.azrael.galleryflow.ProviderWebActivity::class.java)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,
                            if(item.kind=="xphoto")item.source else item.url))
                }else when(item.kind){
                    "xphoto"->{
                        val handle=Uri.parse(item.source).pathSegments.firstOrNull()
                        a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                            .putExtra(XMediaCaptureActivity.EXTRA_HANDLE,handle))
                    }
                    "koikatsu"->{
                        a.startActivity(Intent(a,KoikatsuCardActivity::class.java))
                    }
                    "gallery"->{
                        a.startActivity(Intent(a,
                            com.azrael.galleryflow.ProviderWebActivity::class.java)
                            .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,
                                item.url))
                    }
                    "video"->{
                        val added=VideoDb(a).use{it.addVideo(item.url,item.title)}
                        if(added)VideoEngine.launch(a,false)
                    }
                    "epub"->ResourceDownloads.downloadFile(a,item.url,"Books",
                        item.url.substringBefore('?').substringAfterLast('/'))
                    "model","archive","apk","image","torrent"->
                        ResourceDownloads.route(a,item.url,
                            when{
                                item.area=="COMICS"->"Comics"
                                item.area=="GAMES" || item.kind in setOf("torrent","apk")->"Games"
                                item.area=="ASSETS" || item.kind=="model"->"Assets"
                                else->"Images"
                            })
                    else->Toast.makeText(a,
                        "This result is metadata. Open the item to select its original file.",
                        Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Close",null).show()
    }
}
