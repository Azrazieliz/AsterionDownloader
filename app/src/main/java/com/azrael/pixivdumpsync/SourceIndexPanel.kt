package com.azrael.pixivdumpsync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*
import com.azrael.galleryflow.SiteLoginActivity
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import kotlin.concurrent.thread

/** Kuroha-style thumbnails and one set of controls per content module, never per web site. */
object SourceIndexPanel {
    private val ui=NocturneDesign
    fun render(a:Activity,parent:LinearLayout,area:String){
        val sources=SourceIndex.status(a,area)
        val items=SourceIndex.items(a,area,35)
        if(sources.isEmpty())return
        val card=ui.card(a)
        val healthy=sources.count{it.enabled && it.error.isNullOrEmpty() && it.lastCheck>0}
        val problems=sources.count{!it.error.isNullOrEmpty()}
        card.addView(ui.text(a,"Latest · "+area.lowercase().replaceFirstChar{it.uppercase()},
            17f,ui.white,true))
        card.addView(ui.text(a,
            sources.size.toString()+" sources · "+healthy+" checked · "+
                problems+" need attention · "+items.size+" indexed previews",
            12f,if(problems>0)ui.secondary else ui.primary),ui.lp(a,4))
        val toolbar=ui.row(a)
        toolbar.addView(ui.action(a,"SYNC & BACKFILL",false){
            SourceIndex.scanNow(a,area)
            Toast.makeText(a,"Sync started · results appear in this module",Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(ui.action(a,"SOURCE STATUS",false){
            showStatus(a,area)
        },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        card.addView(toolbar,ui.lp(a,9))
        if(items.isNotEmpty()){
            val scroll=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false}
            val horizontal=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            for(item in items.take(25)){
                val tile=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    background=ui.shape(a,ui.raised,12)
                    setPadding(ui.px(a,6),ui.px(a,6),ui.px(a,6),ui.px(a,6))
                    setOnClickListener{details(a,item)}
                }
                val image=ImageView(a).apply{
                    scaleType=ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(ui.surface)
                    contentDescription=item.title+" · "+item.kind
                }
                tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,126)))
                if(item.preview!=null)RemoteImageLoader.load(a,image,item.preview,item.source)
                tile.addView(ui.text(a,item.title.take(54),12f,ui.white,true),ui.lp(a,5))
                tile.addView(ui.text(a,item.kind.uppercase(),10f,ui.secondary),ui.lp(a,3))
                horizontal.addView(tile,LinearLayout.LayoutParams(ui.px(a,150),-2)
                    .apply{rightMargin=ui.px(a,8)})
            }
            scroll.addView(horizontal)
            card.addView(scroll,ui.lp(a,10))
        }else{
            card.addView(ui.text(a,"No indexed previews yet. The native module remains usable.",
                12f,ui.secondary),ui.lp(a,8))
        }
        parent.addView(card,ui.lp(a,10))
    }
    private fun details(a:Activity,item:IndexedItem){
        val host=runCatching{Uri.parse(item.source).host.orEmpty()}.getOrDefault("")
        val canDownload=item.kind in setOf("video","epub","model","apk","archive","image")
        val options=if(canDownload)
            arrayOf("Download original","Open this item explicitly","Sign in to provider")
        else arrayOf("Open this item explicitly","Sign in to provider")
        AlertDialog.Builder(a).setTitle(item.title)
            .setMessage(host+" · "+item.kind+
                if(item.queued)"\nOriginal queued" else "\nIndexed metadata; not downloaded")
            .setItems(options){_,i->
                when(if(canDownload)i else i+1){
                    0->when(item.kind){
                        "video"->{
                            val added=VideoDb(a).use{it.addVideo(item.url,item.title)}
                            if(added)VideoEngine.launch(a,false)
                        }
                        "epub"->ResourceDownloads.downloadFile(a,item.url,"Books",
                            item.url.substringBefore('?').substringAfterLast('/'))
                        "model","archive","apk","image"->ResourceDownloads.route(a,item.url,
                            if(item.kind=="image")"Images" else "Assets")
                    }
                    1->a.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(item.url)))
                    2->a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,item.source))
                }
            }.setNegativeButton("Close",null).show()
    }
    private fun showStatus(a:Activity,area:String){
        val list=SourceIndex.status(a,area)
        val body=LinearLayout(a).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(ui.px(a,14),ui.px(a,8),ui.px(a,14),ui.px(a,14))
        }
        for(source in list){
            val label=source.name+" · "+(if(!source.enabled)"PAUSED"
                else if(source.adapter=="native")"Native engine"
                else if(source.lastCheck==0L)"Waiting for first scan"
                else if(!source.error.isNullOrEmpty())"Error: "+source.error.take(95)
                else "Indexed "+source.discovered+" items")
            val row=ui.row(a)
            val view=ui.text(a,label,12f,ui.secondary)
            row.addView(view,LinearLayout.LayoutParams(0,-2,1f))
            val toggle=Switch(a).apply{
                isChecked=source.enabled
                setOnCheckedChangeListener{_,checked->
                    SourceIndexDb(a).use{it.enable(source.url,checked)}
                }
            }
            row.addView(toggle)
            body.addView(row,ui.lp(a,8))
        }
        AlertDialog.Builder(a).setTitle(area+" · Source health")
            .setView(ScrollView(a).apply{addView(body)})
            .setPositiveButton("Close",null).show()
    }
}
