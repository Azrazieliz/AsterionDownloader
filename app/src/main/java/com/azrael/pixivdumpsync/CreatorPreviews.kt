package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Size
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import com.azrael.galleryflow.GalleryDb
import com.azrael.galleryflow.GalleryDetailActivity
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import java.net.URI
import java.util.concurrent.Executors

/** Visual creator-following previews shared by X and Kyora, matching Pixiv's archive hierarchy. */
object CreatorPreviews {
    private val ui=NocturneDesign
    private val images=Executors.newFixedThreadPool(3)
    private fun tile(a:Activity,parent:LinearLayout,title:String,source:String,click:()->Unit,
                     render:(ImageView)->Unit){
        val c=ui.card(a)
        val preview=ImageView(a).apply{
            scaleType=ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(ui.raised)
        }
        c.addView(preview,LinearLayout.LayoutParams(ui.px(a,140),ui.px(a,125)))
        c.addView(ui.text(a,title.take(27),12f,ui.white,true),ui.lp(a,6))
        c.addView(ui.text(a,source.take(24),11f,ui.secondary),ui.lp(a,3))
        c.setOnClickListener{click()}
        c.contentDescription=title+" · "+source
        parent.addView(c,LinearLayout.LayoutParams(ui.px(a,164),-2).apply{
            rightMargin=ui.px(a,9)
        })
        render(preview)
    }
    private fun strip(a:Activity,p:LinearLayout,label:String,description:String,
                      build:(LinearLayout)->Unit){
        p.addView(ui.text(a,label,18f,ui.white,true),ui.lp(a,17))
        p.addView(ui.text(a,description,12f,ui.secondary),ui.lp(a,5))
        val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        build(row)
        if(row.childCount>0){
            p.addView(HorizontalScrollView(a).apply{
                isHorizontalScrollBarEnabled=false
                addView(row)
            },ui.lp(a,10))
        }else p.addView(ui.text(a,"No discovered artwork yet. Sync creators to populate thumbnails.",
            12f,ui.secondary),ui.lp(a,7))
    }
    fun galleryThumbnail(a:Activity,view:ImageView,uriValue:String?,remoteUrl:String?){
        val local=uriValue?.takeIf{it.isNotBlank()}
        val remote=remoteUrl?.takeIf{it.startsWith("https://") || it.startsWith("http://")}
        if(local!=null){
            images.execute{
                val bitmap=runCatching{
                    a.contentResolver.loadThumbnail(Uri.parse(local),Size(256,256),null)
                }.getOrNull()
                if(bitmap!=null)view.post{view.setImageBitmap(bitmap)}
                else if(remote!=null)view.post{RemoteImageLoader.load(a,view,remote,remote)}
            }
        }else if(remote!=null)RemoteImageLoader.load(a,view,remote,remote)
    }
    fun x(a:Activity,p:LinearLayout){
        val previews=XPhotoQueue(a).use{db->
            val followed=db.allSources().filter{it.enabled}.map{it.url}.toSet()
            val lists=a.getSharedPreferences("asterion_x_lists",0)
                .getStringSet("urls",emptySet()).orEmpty()
            db.recent(500).filter{it.source in followed || it.source in lists || it.source=="https://x.com/home"}
        }.distinctBy{it.photo}.take(18)
        strip(a,p,"X · LATEST FOLLOWED ARTWORKS",
            "Thumbnails from saved and newly discovered X photos. Tap an artist to see their media."){
            row->
            for(photo in previews){
                val handle=runCatching{URI(photo.source).path.trim('/').substringBefore('/')}
                    .getOrDefault("")
                val list=XMediaCaptureActivity.normalizeList(photo.source)
                val isFeed=photo.source=="https://x.com/home"
                tile(a,row,if(isFeed)"Following feed" else if(list!=null)"X list"
                    else "@"+handle,photo.state,{
                    if(isFeed || list!=null ||
                        handle.matches(Regex("[A-Za-z0-9_]{1,15}")))
                        a.startActivity(Intent(a,XMediaCaptureActivity::class.java).apply{
                            if(isFeed)putExtra(XMediaCaptureActivity.EXTRA_FEED,true)
                            else if(list!=null)putExtra(XMediaCaptureActivity.EXTRA_LIST_URL,list)
                            else putExtra(XMediaCaptureActivity.EXTRA_HANDLE,handle)
                        })
                }){view->RemoteImageLoader.load(a,view,photo.photo,"https://x.com/")}
            }
        }
    }
    fun kyora(a:Activity,p:LinearLayout){
        val cards=GalleryDb(a).use{it.latestSelectedGalleries(40)}
            .filter{!it.second.firstUri.isNullOrBlank() || !it.second.firstUrl.isNullOrBlank()}.take(30)
        strip(a,p,"KYORA · LATEST FOLLOWED GALLERIES",
            "Recent galleries with locally saved thumbnail covers. Tap a gallery to browse originals."){
            row->
            for((creator,card) in cards){
                tile(a,row,card.title,creator,{
                    a.startActivity(Intent(a,GalleryDetailActivity::class.java).apply{
                        putExtra(GalleryDetailActivity.EXTRA_GALLERY_ID,card.galleryId)
                        putExtra(GalleryDetailActivity.EXTRA_SOURCE,card.source.wireName)
                        putExtra(GalleryDetailActivity.EXTRA_TITLE,card.title)
                    })
                }){view->
                    galleryThumbnail(a,view,card.firstUri,card.firstUrl)
                }
            }
        }
    }
    fun sourceThumbnail(a:Activity,view:ImageView,sourceUrl:String){
        if(!SearchRanking.validUrl(sourceUrl))return
        images.execute{
            val preview=runCatching{
                val page=org.jsoup.Jsoup.connect(sourceUrl).userAgent("Mozilla/5.0 Android")
                    .timeout(12000).maxBodySize(500000).get()
                val result=page.selectFirst("meta[property=og:image]")?.attr("content")
                    ?:page.selectFirst("meta[name=twitter:image]")?.attr("content")
                    ?:page.selectFirst("article img[src]")?.absUrl("src")
                val url=java.net.URI(page.location()).resolve(result.orEmpty()).toString()
                url.takeIf{SearchRanking.validUrl(it)}
            }.getOrNull()
            if(preview!=null)view.post{RemoteImageLoader.load(a,view,preview,sourceUrl)}
        }
    }
    fun syncKyoraVideos(a:Activity):Int{
        val sites=GalleryDb(a).use{it.listEntities().filter{row->row.selected}}
        var count=0
        VideoDb(a).use{videos->
            sites.forEach{entity->
                if(videos.addWatch(entity.canonicalUrl,entity.displayName+" · cosplay videos"))
                    count++
            }
        }
        VideoEngine.launch(a,true)
        return count
    }
}
