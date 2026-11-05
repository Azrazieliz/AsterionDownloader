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
        // A native adapter being installed does not establish a successful
        // provider backfill. Display separately from actually checked sources.
        val native=sources.count{it.enabled && it.adapter=="native"}
        val checked=sources.count{it.enabled &&
            it.adapter=="generic" && it.lastCheck>0 && it.error.isNullOrEmpty()}
        val problems=sources.count{!it.error.isNullOrEmpty()}
        val accounts=sources.count{it.enabled &&
            com.azrael.galleryflow.SiteSession.bound(a,it.url)}
        card.addView(ui.text(a,"Latest · "+label.lowercase().replaceFirstChar{it.uppercase()},
            17f,ui.white,true))
        card.addView(ui.text(a,
            sources.distinctBy{SiteControls.key(it.url)}.size.toString()+
                " sites · "+checked+" index-checked · "+problems+
                " errors · "+items.size+" previews"+
                " · native coverage not assumed",
            12f,if(problems>0)ui.secondary else ui.primary),ui.lp(a,4))
        val toolbar=ui.row(a)
        toolbar.addView(ui.action(a,"RUN ENABLED SOURCES",false){
            thread(name="asterion-module-backfill"){
                for(area in areas)SourceIndex.scan(a,area)
            }
            if(areas.any{it=="ADULT"||it=="HENTAI"})VideoEngine.launch(a,true)
            if(areas.any{it=="CREATORS"||it=="ASSETS"})CreatorArchiveScheduler.scanNow(a)
            if("COMICS" in areas)ChapterArchives.start(a)
            if("COSPLAY" in areas)runCatching{
                for(mode in listOf(com.azrael.galleryflow.SyncMode.LIVE,
                    com.azrael.galleryflow.SyncMode.BACKFILL)){
                    a.startForegroundService(Intent(a,
                        com.azrael.galleryflow.SyncForegroundService::class.java)
                        .setAction(com.azrael.galleryflow.SyncForegroundService.ACTION_START)
                        .putExtra(com.azrael.galleryflow.SyncForegroundService.EXTRA_MODE,
                            mode.name))
                }
            }
            Toast.makeText(a,"Source sync requested · new previews appear here",
                Toast.LENGTH_SHORT).show()
        },LinearLayout.LayoutParams(0,-2,1f))
        toolbar.addView(ui.action(a,"SITES · SYNC / BACKFILL",false){
            showStatus(a,areas,label)
        },LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=ui.px(a,8)})
        card.addView(toolbar,ui.lp(a,9))
        if("BOOKS" in areas){
            val epub=NovelEpubQueue(a).use{it.snapshot()}
            card.addView(ui.text(a,"EPUB collections · "+epub.complete+
                " completed · "+epub.active+" active · "+epub.pending+
                " scheduled",12f,ui.secondary),ui.lp(a,7))
            if(epub.pending>0){
                card.addView(ui.action(a,"RESUME EPUB COLLECTIONS",false){
                    NovelEpubService.schedule(a)
                    Toast.makeText(a,"EPUB queue scheduled",
                        Toast.LENGTH_SHORT).show()
                },ui.lp(a,6))
            }
            epub.lastError?.let{message->
                card.addView(ui.text(a,"Last EPUB error: "+message.take(160),
                    11f,ui.secondary),ui.lp(a,5))
            }
        }
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
    internal fun details(a:Activity,item:IndexedItem){
        val host=runCatching{Uri.parse(item.source).host.orEmpty()}
            .getOrDefault("")
        AlertDialog.Builder(a).setTitle(item.title)
            .setMessage(host+" · "+if(item.queued)
                "Transfer previously requested" else
                "Indexed media · not yet saved")
            .setPositiveButton("DOWNLOAD ORIGINAL"){_,_->
                if(item.area=="BOOKS" &&
                    NovelEpubParser.supportedSeries(item.url)){
                    NovelEpubService.enqueue(a,item.url,item.title)
                }else AuthenticatedItemDownloader.download(a,item)
            }
            .setNeutralButton("SIGN IN"){_,_->
                a.startActivity(Intent(a,SiteLoginActivity::class.java)
                    .putExtra(SiteLoginActivity.EXTRA_URL,item.source))
            }
            .setNegativeButton("CANCEL",null).show()
    }
    private fun showStatus(a:Activity,areas:List<String>,label:String){
        // Each host appears ONCE. Native categories and old album rows are
        // implementation details, not separate user-facing subscriptions.
        val sites=areas.flatMap{SourceIndex.status(a,it)}
            .filter{SiteControls.key(it.url).isNotBlank()}
            .groupBy{SiteControls.key(it.url)}
            .toSortedMap().map{(host,entries)->
                host to entries
            }
        if(sites.isEmpty()){
            Toast.makeText(a,"No sources registered yet",Toast.LENGTH_SHORT).show()
            return
        }
        val list=ListView(a).apply{
            dividerHeight=ui.px(a,6)
            setPadding(ui.px(a,8),ui.px(a,8),ui.px(a,8),ui.px(a,8))
            clipToPadding=false
        }
        list.adapter=object:BaseAdapter(){
            override fun getCount()=sites.size
            override fun getItem(position:Int):Any=sites[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:android.view.View?,
                                 parent:android.view.ViewGroup?):android.view.View{
                val (host,group)=sites[position]
                val site=group.firstOrNull{it.adapter=="native"}?:group.first()
                val name=if(host=="4khd.com")"4KHD" else
                    AlohaSources.all.firstOrNull{SiteControls.key(it.url)==host}
                        ?.name?:site.name
                val root=convertView as? LinearLayout?:ui.card(a)
                root.removeAllViews()
                root.addView(ui.text(a,name,15f,ui.white,true),ui.lp(a,2))
                val issues=group.mapNotNull{it.error?.takeIf(String::isNotBlank)}
                val checked=group.filter{it.adapter=="generic" && it.lastCheck>0}
                val progress=when{
                    issues.isNotEmpty()->"ERROR · "+issues.first().take(110)
                    checked.isNotEmpty()->"Indexed "+
                        checked.sumOf{it.discovered}+" observed items · "+
                        if(checked.any{!it.cursor.isNullOrBlank()})
                            "older pages remain" else "no next page detected"
                    group.any{it.adapter=="native"}->
                        "Native download engine · history not verified"
                    else->"Not checked yet"
                }
                root.addView(ui.text(a,progress,11f,
                    if(issues.isNotEmpty())ui.danger else ui.secondary),
                    ui.lp(a,3))
                val flags=ui.row(a)
                val sync=Switch(a).apply{
                    text="Sync"
                    textSize=12f
                    setTextColor(ui.white)
                    isChecked=SiteControls.sync(a,site.url,
                        group.any{it.enabled})
                    contentDescription="Sync new items on "+name
                    setOnCheckedChangeListener{_,enabled->
                        SiteControls.setSync(a,site.url,enabled)
                        thread(name="asterion-site-sync-pref"){
                            SiteControls.applyToEngines(a.applicationContext,site.url)
                        }
                    }
                }
                val backfill=Switch(a).apply{
                    text="Backfill"
                    textSize=12f
                    setTextColor(ui.white)
                    isChecked=SiteControls.backfill(a,site.url,
                        group.any{it.enabled})
                    contentDescription="Backfill older items on "+name
                    setOnCheckedChangeListener{_,enabled->
                        SiteControls.setBackfill(a,site.url,enabled)
                        thread(name="asterion-site-backfill-pref"){
                            SiteControls.applyToEngines(a.applicationContext,site.url)
                        }
                    }
                }
                flags.addView(sync,LinearLayout.LayoutParams(0,-2,1f))
                flags.addView(backfill,LinearLayout.LayoutParams(0,-2,1f))
                root.addView(flags,ui.lp(a,3))
                val commands=ui.row(a)
                commands.addView(ui.action(a,"CHECK NOW",false){
                    if(!AppPower.enabled(a)){
                        Toast.makeText(a,"App is paused in Settings",
                            Toast.LENGTH_SHORT).show()
                    }else if(!SiteControls.any(a,site.url)){
                        Toast.makeText(a,"Enable Sync or Backfill for "+name,
                            Toast.LENGTH_SHORT).show()
                    }else thread(name="asterion-check-provider"){
                        SiteControls.applyToEngines(a.applicationContext,site.url)
                        val result=runCatching{
                            ProviderBackfillActions.request(a,site)
                        }.getOrElse{it.message?:"Source request failed"}
                        a.runOnUiThread{
                            if(!a.isFinishing)Toast.makeText(a,result,
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                commands.addView(ui.action(a,"LOGIN",false){
                    a.startActivity(Intent(a,SiteLoginActivity::class.java)
                        .putExtra(SiteLoginActivity.EXTRA_URL,site.url))
                },LinearLayout.LayoutParams(0,-2,1f).apply{
                    leftMargin=ui.px(a,8)
                })
                root.addView(commands,ui.lp(a,5))
                return root
            }
        }
        // ListView virtualizes rows; no 50-card ScrollView allocation or
        // synchronous source rescan on every setting change.
        val maxHeight=(a.resources.displayMetrics.heightPixels*0.67f).toInt()
        list.layoutParams=LinearLayout.LayoutParams(-1,maxHeight)
        AlertDialog.Builder(a).setTitle(label+" · "+sites.size+" sites")
            .setView(list).setPositiveButton("Close",null).show()
    }
}
