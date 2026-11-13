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
        val items=areas.flatMap{SourceIndex.items(a,it,500)}
            .filter{AsterionAdultHub.realMedia(it)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedByDescending{it.discoveredAt}.take(500)
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
        card.addView(ui.text(a,"By website · "+label.lowercase().replaceFirstChar{it.uppercase()},
            17f,ui.white,true))
        card.addView(ui.text(a,
            sources.distinctBy{SiteControls.key(it.url)}.size.toString()+
                " sites · "+checked+" index-checked · "+problems+
                " errors · "+items.size+" indexed items"+
                " · native coverage not assumed",
            12f,if(problems>0)ui.secondary else ui.primary),ui.lp(a,4))
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
            val groups=items.groupBy{SiteControls.key(it.source)}
                .filterKeys{it.isNotBlank()}
                .toList().sortedBy{(host,_) ->
                    AlohaSources.all.firstOrNull{SiteControls.key(it.url)==host}
                        ?.name?.lowercase()?:"zz"+host
                }
            val prefs=a.getSharedPreferences("asterion_grouped_media",0)
            val key="limit_"+areas.sorted().joinToString("_")
            val limit=prefs.getInt(key,10).coerceIn(5,80)
            for((host,owned) in groups.take(limit)){
                val group=ui.card(a,8)
                val display=AlohaSources.all.firstOrNull{
                    SiteControls.key(it.url)==host
                }?.name ?: sources.firstOrNull{
                    SiteControls.key(it.url)==host
                }?.name?:host
                val header=ui.row(a).apply{gravity=android.view.Gravity.CENTER_VERTICAL}
                val textBlock=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
                textBlock.addView(ui.text(a,display,15f,ui.white,true).apply{
                    maxLines=1
                })
                textBlock.addView(ui.text(a,
                    owned.size.toString()+" indexed items · not verified downloads",
                    11f,ui.secondary),ui.lp(a,3))
                header.addView(textBlock,LinearLayout.LayoutParams(0,-2,1f))
                group.addView(header)
                val visual=owned.filter{
                    !it.preview.isNullOrBlank() &&
                        SearchRanking.validUrl(it.preview)
                }
                if(visual.isNotEmpty()){
                    KurohaMediaPreview.render(a,group,
                        "source:"+areas.joinToString("_")+":"+host,
                        visual.map{item->KurohaMediaPreview.Tile(
                            url=item.url,title=item.title,
                            thumbnail=item.preview.orEmpty(),
                            attribution=display,referer=item.source,
                            video=item.kind=="video"
                        )}){tile->
                        visual.firstOrNull{it.url==tile.url}
                            ?.let{details(a,it)}
                    }
                }else{
                    group.addView(ui.text(a,
                        "No actual image previews indexed for this website.",
                        12f,ui.secondary),ui.lp(a,7))
                }
                val without=owned.filter{
                    it.preview.isNullOrBlank() ||
                        !SearchRanking.validUrl(it.preview)
                }
                if(without.isNotEmpty()){
                    val hidden=LinearLayout(a).apply{
                        orientation=LinearLayout.VERTICAL
                    }
                    val button=ui.action(a,
                        "ITEMS WITHOUT PREVIEWS · "+without.size,false){
                        if(hidden.childCount>0)hidden.removeAllViews()
                        else for(item in without.take(12)){
                            hidden.addView(ui.action(a,
                                "DOWNLOAD · "+item.title.take(44),false){
                                details(a,item)
                            },ui.lp(a,5))
                        }
                    }
                    group.addView(button,ui.lp(a,7))
                    group.addView(hidden)
                }
                card.addView(group,ui.lp(a,7))
            }
            if(groups.size>limit){
                card.addView(ui.action(a,
                    "SHOW MORE WEBSITES · "+(groups.size-limit),false){
                    prefs.edit().putInt(key,(limit+10).coerceAtMost(80)).apply()
                    // Update just the provider section without asking the user
                    // to navigate through another browser or destination.
                    a.recreate()
                },ui.lp(a,8))
            }
        }else{
            card.addView(ui.text(a,
                "No site-owned media indexed here. Select Sources to manage "+
                    "individual Sync and Backfill; previews are never invented.",
                12f,ui.secondary),ui.lp(a,8))
        }
        if("COSPLAY" !in areas)card.addView(
            ui.action(a,"MANAGE WEBSITES · SYNC & BACKFILL",false){
                showStatus(a,areas,label)
            },ui.lp(a,9))
        if("COSPLAY" in areas){
            // Approved sources are always available, but source configuration
            // never buries real creator/gallery previews.
            val pref=a.getSharedPreferences("asterion_cosplay_manager",0)
            val key="websites_expanded"
            val approved=AlohaSources.by(AlohaArea.COSPLAY)
            val registered=sources.groupBy{SiteControls.key(it.url)}
            val manager=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            fun repaint(){
                manager.removeAllViews()
                if(!pref.getBoolean(key,false))return
            for(site in approved){
                val known=registered[SiteControls.key(site.url)].orEmpty()
                val record=known.firstOrNull{it.adapter=="native"}
                    ?:known.firstOrNull()
                val url=record?.url?:site.url
                val tile=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    background=ui.shape(a,ui.raised,11)
                    setPadding(ui.px(a,10),ui.px(a,9),
                        ui.px(a,10),ui.px(a,9))
                }
                val title=ui.row(a)
                val name=ui.text(a,site.name,14f,ui.white,true)
                title.addView(name,LinearLayout.LayoutParams(0,-2,1f))
                val status=when{
                    record==null->"Not registered"
                    !record.error.isNullOrBlank()->"Needs repair"
                    record.lastCheck>0->"Checked · "+record.discovered+" indexed"
                    record.adapter=="native"->"Native source · not verified"
                    else->"Not checked yet"
                }
                title.addView(ui.text(a,status,11f,
                    if(record?.error.isNullOrBlank()==false)ui.danger else ui.secondary))
                tile.addView(title)
                val actions=ui.row(a).apply{
                    gravity=android.view.Gravity.CENTER_VERTICAL
                }
                val enabled=SiteControls.explicitlyEnabled(a,url)
                val sync=Switch(a).apply{
                    text="Sync new";textSize=12f;setTextColor(ui.secondary)
                    isChecked=enabled
                    contentDescription="Sync "+site.name
                    setOnCheckedChangeListener{_,on->
                        SiteControls.setSync(a,url,on)
                        thread(name="asterion-cosplay-site-pref"){
                            SiteControls.applyToEngines(a.applicationContext,url)
                        }
                    }
                }
                actions.addView(sync,LinearLayout.LayoutParams(0,-2,1f))
                actions.addView(ui.action(a,"BACKFILL",false){
                    if(!AppPower.enabled(a)){
                        Toast.makeText(a,"Resume downloads first",
                            Toast.LENGTH_SHORT).show()
                    }else{
                        thread(name="asterion-cosplay-"+site.host.take(14)){
                            // Existing site preview does not require the
                            // SourceIndex record to have been created yet.
                            val message=runCatching{
                                ApprovedCosplayActions.backfill(a,site.url)
                            }.getOrElse{it.message?:"Backfill failed"}
                            a.runOnUiThread{
                                if(!a.isFinishing)Toast.makeText(a,message,
                                    Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },LinearLayout.LayoutParams(0,-2,1f))
                tile.addView(actions,ui.lp(a,6))
                if(!record?.error.isNullOrBlank()){
                    tile.addView(ui.text(a,record?.error.orEmpty().take(125),
                        11f,ui.danger),ui.lp(a,4))
                }
                manager.addView(tile,ui.lp(a,5))
            }

            }
            lateinit var toggle:android.widget.TextView
            toggle=ui.action(a,
                if(pref.getBoolean(key,false))"HIDE WEBSITE CONTROLS"
                else "MANAGE WEBSITES · "+approved.size,false){
                val next=!pref.getBoolean(key,false)
                pref.edit().putBoolean(key,next).apply()
                toggle.text=if(next)"HIDE WEBSITE CONTROLS"
                    else "MANAGE WEBSITES · "+approved.size
                repaint()
            }
            card.addView(toggle,ui.lp(a,9))
            card.addView(manager)
            repaint()
        }
        parent.addView(card,ui.lp(a,10))
    }
    /** A media tap initiates the original-file path; it does not open
     * a website or an intermediary source-page dialog.
     */
    internal fun details(a:Activity,item:IndexedItem){
        if(item.queued){
            Toast.makeText(a,"Already queued · check Downloads",
                Toast.LENGTH_SHORT).show()
            return
        }
        if(item.area=="BOOKS" && NovelEpubParser.supportedSeries(item.url)){
            val created=NovelEpubService.enqueue(a,item.url,item.title)
            Toast.makeText(a,
                if(created)"EPUB queued in Downloads" else
                    "Series already followed",Toast.LENGTH_SHORT).show()
        }else AuthenticatedItemDownloader.download(a,item)
    }
    internal fun showStatus(a:Activity,areas:List<String>,label:String){
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
                flags.addView(sync,LinearLayout.LayoutParams(0,-2,1f))
                root.addView(flags,ui.lp(a,3))
                val commands=ui.row(a)
                commands.addView(ui.action(a,"BACKFILL",false){
                    if(!AppPower.enabled(a)){
                        Toast.makeText(a,"App is paused in Settings",
                            Toast.LENGTH_SHORT).show()
                    }else thread(name="asterion-check-provider"){
                        SiteControls.applyToEngines(a.applicationContext,site.url)
                        val result=runCatching{
                            if(site.adapter=="generic"){
                                if(SiteRunControl.start(a,site.url,site.area))
                                    "Backfill started for "+name
                                else "Already checking "+name
                            }else ProviderBackfillActions.request(a,site)
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
