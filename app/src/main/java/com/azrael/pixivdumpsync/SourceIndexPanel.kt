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
    fun render(a:Activity,parent:LinearLayout,area:String)=render(a,parent,listOf(area),area)
    fun render(a:Activity,parent:LinearLayout,areas:List<String>,label:String){
        val sources=areas.flatMap{SourceIndex.status(a,it)}.distinctBy{it.url}
        val items=areas.flatMap{SourceIndex.items(a,it,35)}
            .distinctBy{it.url}.sortedByDescending{it.discoveredAt}.take(35)
        if(sources.isEmpty())return
        val card=ui.card(a)
        val healthy=sources.count{
            it.enabled && it.error.isNullOrEmpty() &&
                (it.adapter=="native" || it.lastCheck>0)
        }
        val problems=sources.count{!it.error.isNullOrEmpty()}
        val accounts=sources.count{it.enabled &&
            com.azrael.galleryflow.SiteSession.bound(a,it.url)}
        card.addView(ui.text(a,"Latest · "+label.lowercase().replaceFirstChar{it.uppercase()},
            17f,ui.white,true))
        card.addView(ui.text(a,
            sources.size.toString()+" sources · "+accounts+" connected · "+
                healthy+" active · "+problems+" errors · "+items.size+" previews",
            12f,if(problems>0)ui.secondary else ui.primary),ui.lp(a,4))
        val toolbar=ui.row(a)
        toolbar.addView(ui.action(a,"SYNC & BACKFILL",false){
            thread(name="asterion-module-backfill"){
                for(area in areas)SourceIndex.scan(a,area)
            }
            if(areas.any{it=="ADULT"||it=="HENTAI"})VideoEngine.launch(a,true)
            if(areas.any{it=="CREATORS"||it=="ASSETS"})CreatorArchiveScheduler.scanNow(a)
            if("COMICS" in areas)ChapterArchives.start(a)
            if("COSPLAY" in areas)runCatching{
                a.startForegroundService(Intent(a,
                    com.azrael.galleryflow.SyncForegroundService::class.java)
                    .setAction(com.azrael.galleryflow.SyncForegroundService.ACTION_START)
                    .putExtra(com.azrael.galleryflow.SyncForegroundService.EXTRA_MODE,
                        com.azrael.galleryflow.SyncMode.BACKFILL.name))
            }
            Toast.makeText(a,"Source sync requested · new previews appear here",
                Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(ui.action(a,"SITES & LOGIN",false){
            showStatus(a,areas,label)
        },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        card.addView(toolbar,ui.lp(a,9))
        if(items.isNotEmpty()){
            UnifiedMediaGrid.render(a,card,items,areas,label){item->
                details(a,item)
            }
        }else{
            card.addView(ui.text(a,
                "No indexed previews yet. Native creator galleries remain accessible above.",
                12f,ui.secondary),ui.lp(a,8))
        }
        parent.addView(card,ui.lp(a,10))
    }
    private fun details(a:Activity,item:IndexedItem){
        val host=runCatching{Uri.parse(item.source).host.orEmpty()}.getOrDefault("")
        AlertDialog.Builder(a).setTitle(item.title)
            .setMessage(host+" · "+item.kind+
                if(item.queued)"\nOriginal queued" else "\nIndexed metadata; not downloaded")
            .setItems(arrayOf("DOWNLOAD ACCESSIBLE ORIGINAL",
                "VIEW IN APP (SESSION)","SIGN IN TO PROVIDER")){_,i->
                when(i){
                    0->{
                        if(item.area=="BOOKS" && NovelEpubParser.supportedSeries(item.url)){
                            NovelEpubService.enqueue(a,item.url,item.title)
                            Toast.makeText(a,"Entire novel queued for EPUB assembly",
                                Toast.LENGTH_LONG).show()
                        }else AuthenticatedItemDownloader.download(a,item)
                    }
                    1->a.startActivity(Intent(a,
                        com.azrael.galleryflow.ProviderWebActivity::class.java)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_URL,item.url)
                        .putExtra(com.azrael.galleryflow.ProviderWebActivity.EXTRA_FOLDER,
                            when(item.area){
                                "ADULT","HENTAI","VIDEOS","CREATORS"->"Videos"
                                "ARTWORK"->"Artwork"
                                "COSPLAY"->"Cosplay"
                                "BOOKS"->"Books"
                                "COMICS"->"Comics"
                                "GAMES"->"Games"
                                "ASSETS"->"Assets"
                                else->"References"
                            }))
                    2->a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,item.source))
                }
            }.setNegativeButton("Close",null).show()
    }
    private fun showStatus(a:Activity,areas:List<String>,label:String){
        val list=areas.flatMap{SourceIndex.status(a,it)}.distinctBy{it.url}
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
            if(source.adapter=="native"){
                // Native engines already have persistent per-artist and per-source toggles.
                // A second switch here would not control their real scheduler.
                row.addView(ui.text(a,"Managed in Following",11f,ui.secondary))
            }else{
                val toggle=Switch(a).apply{
                    isChecked=source.enabled
                    contentDescription="Sync "+source.name
                    setOnCheckedChangeListener{_,checked->
                        SourceIndexDb(a).use{it.enable(source.url,checked)}
                    }
                }
                row.addView(toggle)
            }
            body.addView(row,ui.lp(a,8))
        }
        body.addView(ui.action(a,"CONNECT AN ACCOUNT",false){
            val accounts=list.filter{SourceIndexParser.safe(it.url)}
            AlertDialog.Builder(a).setTitle("Select a provider")
                .setItems(accounts.map{it.name}.toTypedArray()){_,index->
                    a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,accounts[index].url))
                }.setNegativeButton("Cancel",null).show()
        },ui.lp(a,14))
        AlertDialog.Builder(a).setTitle(label+" · Source health")
            .setView(ScrollView(a).apply{addView(body)})
            .setPositiveButton("Close",null).show()
    }
}
