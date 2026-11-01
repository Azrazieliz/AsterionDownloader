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
    fun x(a:Activity,p:LinearLayout){
        val previews=XPhotoQueue(a).use{it.recent(150)}
            .distinctBy{it.photo}.take(18)
        strip(a,p,"X · LATEST FOLLOWED ARTWORKS",
            "Thumbnails from saved and newly discovered X photos. Tap an artist to see their media."){
            row->
            for(photo in previews){
                val handle=runCatching{URI(photo.source).path.trim('/').substringBefore('/')}
                    .getOrDefault("")
                tile(a,row,"@"+handle,photo.state,{
                    if(handle.matches(Regex("[A-Za-z0-9_]{1,15}")))
                        a.startActivity(Intent(a,XMediaCaptureActivity::class.java)
                            .putExtra(XMediaCaptureActivity.EXTRA_HANDLE,handle))
                }){view->RemoteImageLoader.load(a,view,photo.photo,"https://x.com/")}
            }
        }
    }
    fun kyora(a:Activity,p:LinearLayout){
        val cards=GalleryDb(a).use{it.latestSelectedGalleries(40)}
            .filter{!it.second.firstUri.isNullOrBlank()}.take(20)
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
                    val uri=Uri.parse(card.firstUri)
                    images.execute{
                        val thumbnail=runCatching{
                            a.contentResolver.loadThumbnail(uri,Size(256,256),null)
                        }.getOrNull()
                        if(thumbnail!=null)view.post{view.setImageBitmap(thumbnail)}
                    }
                }
            }
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
