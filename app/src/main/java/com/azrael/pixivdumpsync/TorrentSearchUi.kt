package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import kotlin.concurrent.thread

/** One visible, category-specific native torrent workflow across the main app. */
object TorrentSearchUi {
    fun render(a:Activity,body:LinearLayout,area:TorrentArea,
               refresh:()->Unit){
        val ui=NocturneDesign
        val name=when(area){TorrentArea.GAMES->"GAMES";TorrentArea.BOOKS->"BOOKS";TorrentArea.ADULT->"ADULT VIDEO"}
        val card=ui.card(a)
        card.addView(ui.text(a,"Find a release · "+name,17f,ui.white,true),ui.lp(a,3))
        card.addView(ui.text(a,"Search by title, then choose the actual files.",12f,ui.secondary),ui.lp(a,5))
        val pref=a.getSharedPreferences("asterion_torrent_search",0)
        val field=EditText(a).apply{
            hint="Name / title / keywords"
            setSingleLine(true)
            setText(pref.getString(area.name+"_query",""))
            setTextColor(ui.white);setHintTextColor(ui.secondary)
            textSize=15f
        }
        card.addView(field,ui.lp(a,7))
        val status=ui.text(a,"No torrent transfer started",12f,ui.secondary)
        val magnetOpen=pref.getBoolean(area.name+"_magnet_open",false)
        card.addView(ui.action(a,
            if(magnetOpen)"HIDE MAGNET / .TORRENT IMPORT"
            else "IMPORT A MAGNET / .TORRENT LINK",false){
            pref.edit().putBoolean(area.name+"_magnet_open",!magnetOpen).apply()
            refresh()
        },ui.lp(a,7))
        if(magnetOpen){
            val magnet=EditText(a).apply{
                hint="Paste magnet or direct .torrent URL"
                setSingleLine(true);setTextColor(ui.white)
                setHintTextColor(ui.secondary);textSize=14f
            }
            card.addView(magnet,ui.lp(a,6))
            card.addView(ui.action(a,"SELECT FILES FROM LINK",false){
                val value=magnet.text.toString().trim()
                if(!value.startsWith("magnet:?xt=urn:",true) &&
                    !(SearchRanking.validUrl(value) &&
                        value.substringBefore('?').endsWith(".torrent",true))){
                    status.text="Enter a magnet or direct .torrent URL"
                    return@action
                }
                ResourceDownloads.route(a,value,when(area){
                    TorrentArea.GAMES->"Games"
                    TorrentArea.BOOKS->"Books"
                    TorrentArea.ADULT->"Videos"
                })
                status.text="Retrieving metadata; choose files before downloading"
            },ui.lp(a,6))
        }
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val records=ArrayList<CatalogHit>()
        fun draw(){
            results.removeAllViews()
            for(hit in records.take(80)){
                val item=ui.row(a).apply{
                    gravity=android.view.Gravity.CENTER_VERTICAL
                    setPadding(ui.px(a,12),ui.px(a,9),ui.px(a,10),ui.px(a,9))
                    background=ui.shape(a,ui.surface,15)
                }
                val details=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                }
                details.addView(ui.text(a,hit.title,14f,ui.white,true).apply{
                    maxLines=2
                    ellipsize=android.text.TextUtils.TruncateAt.END
                })
                details.addView(ui.text(a,hit.source+" · "+hit.details,
                    11f,ui.secondary).apply{maxLines=1
                        ellipsize=android.text.TextUtils.TruncateAt.END
                    },ui.lp(a,4))
                item.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                item.addView(ui.action(a,"FILES",false){
                    status.text="Retrieving torrent metadata; select files before transfer"
                    ResourceDownloads.route(a,hit.url,when(area){
                        TorrentArea.GAMES->"Games"
                        TorrentArea.BOOKS->"Books"
                        TorrentArea.ADULT->"Videos"
                    })
                },LinearLayout.LayoutParams(-2,-2).apply{
                    leftMargin=ui.px(a,8)
                })
                results.addView(item,ui.lp(a,5))
            }
        }
        card.addView(ui.action(a,"SEARCH "+name+" TORRENTS",true){
            val term=field.text.toString().trim()
            if(term.length<2){Toast.makeText(a,"Enter a title",Toast.LENGTH_LONG).show();return@action}
            pref.edit().putString(area.name+"_query",term).apply()
            status.text="Searching multiple torrent libraries…"
            records.clear();draw()
            thread(name="asterion-torrent-query"){
                val response=runCatching{MultiTorrentSearch.search(term,area)}
                a.runOnUiThread{
                    response.onSuccess{(hits,errors)->
                        records+=hits.filter{CatalogDiscovery.relevance(term,it.title)>0}.take(80)
                        status.text=records.size.toString()+" results"+
                            if(errors.isEmpty())"" else "\nUnavailable: "+errors.take(3).joinToString(" | ").take(220)
                        draw()
                    }.onFailure{status.text="Torrent search failed: "+it.message}
                }
            }
        },ui.lp(a,9))
        card.addView(status,ui.lp(a,8))
        card.addView(ui.action(a,"VIEW TRANSFERS",false){
            TorrentTransfers.showQueue(a)
        },ui.lp(a,7))
        body.addView(card,ui.lp(a,12))
        body.addView(results,ui.lp(a,8))
        val registered=TorrentFeedDb(a).use{it.status()}
        val shown=when(area){
            TorrentArea.GAMES->registered.filter{it.source=="NYAA_MIRROR"}
            TorrentArea.ADULT->registered.filter{it.source in setOf("ONEJAV","SUKEBEI")}
            TorrentArea.BOOKS->emptyList()
        }
        val settings=a.getSharedPreferences("asterion_torrent_source_ui",0)
        val sourcesOpen=settings.getBoolean("sources_"+area.name,false)
        if(shown.isNotEmpty()){
            val issues=shown.count{!it.error.isNullOrBlank()}
            body.addView(ui.action(a,
                if(sourcesOpen)"HIDE SOURCES & BACKFILL"
                else "SOURCES & BACKFILL · "+shown.size+
                    (if(issues>0)" · "+issues+" issues" else ""),false){
                settings.edit().putBoolean("sources_"+area.name,!sourcesOpen)
                    .apply()
                refresh()
            },ui.lp(a,8))
        }
        if(sourcesOpen)for(feed in shown){
            val home=TorrentFeedParser.home(feed.source)
            val friendly=when(feed.source){
                "SUKEBEI"->"Sukebei"
                "ONEJAV"->"OneJAV"
                else->"Nyaa"
            }
            val busy=TorrentSourceBackfill.isRunning(feed.source)
            val shell=ui.card(a,11)
            val heading=ui.row(a)
            val names=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
            names.addView(ui.text(a,friendly,15f,ui.white,true))
            names.addView(ui.text(a,
                feed.count.toString()+" indexed releases · "+
                    when{
                        busy->"Backfill running"
                        !feed.error.isNullOrBlank()->"Source error"
                        feed.lastCheck==0L->"Not checked"
                        feed.cursor!=null->"History available"
                        else->"History checked"
                    },11f,if(feed.error!=null)ui.danger else ui.secondary),
                ui.lp(a,3))
            heading.addView(names,LinearLayout.LayoutParams(0,-2,1f))
            heading.addView(Switch(a).apply{
                text="Sync"
                setTextColor(ui.white);textSize=12f
                isChecked=SiteControls.sync(a,home)
                setOnCheckedChangeListener{_,enabled->
                    SiteControls.setSync(a,home,enabled)
                    if(enabled)TorrentSourceBackfill.ensure(a.applicationContext)
                }
            })
            shell.addView(heading)
            val commands=ui.row(a)
            commands.addView(ui.action(a,if(busy)"STOP" else "BACKFILL",!busy){
                if(busy)TorrentSourceBackfill.stopSite(feed.source)
                else if(!AppPower.enabled(a))Toast.makeText(a,
                    "Resume downloads first",Toast.LENGTH_SHORT).show()
                else TorrentSourceBackfill.startSite(a.applicationContext,feed.source)
                Toast.makeText(a,if(busy)"Stopping after current request"
                    else "History check requested",Toast.LENGTH_SHORT).show()
            },LinearLayout.LayoutParams(0,-2,1f))
            val expanded=settings.getBoolean("open_"+feed.source,false)
            commands.addView(ui.action(a,if(expanded)"HIDE RESULTS" else "RESULTS",false){
                settings.edit().putBoolean("open_"+feed.source,!expanded).apply()
                // Only this pane changes, no global or duplicated module.
                refresh()
            },LinearLayout.LayoutParams(0,-2,1f).apply{
                leftMargin=ui.px(a,7)
            })
            shell.addView(commands,ui.lp(a,7))
            if(!feed.error.isNullOrBlank())
                shell.addView(ui.text(a,
                    "Last source error: "+feed.error.take(120),11f,ui.danger),
                    ui.lp(a,6))
            if(expanded){
                val indexed=TorrentFeedDb(a).use{it.discovered(feed.source,30)}
                if(indexed.isEmpty()){
                    shell.addView(ui.text(a,"No torrent releases indexed from this source yet.",
                        12f,ui.secondary),ui.lp(a,8))
                }
                for(item in indexed){
                    val result=ui.row(a).apply{
                        gravity=android.view.Gravity.CENTER_VERTICAL
                    }
                    val details=LinearLayout(a).apply{
                        orientation=LinearLayout.VERTICAL
                    }
                    details.addView(ui.text(a,item.title.take(100),12f,ui.white,true)
                        .apply{maxLines=2})
                    details.addView(ui.text(a,
                        "Indexed only · select files to download",
                        10f,ui.secondary),ui.lp(a,3))
                    result.addView(details,LinearLayout.LayoutParams(0,-2,1f))
                    result.addView(ui.action(a,"FILES",false){
                        status.text="Resolving torrent file metadata"
                        thread(name="asterion-torrent-select"){
                            val target=runCatching{
                                item.download?:TorrentSourceBackfill.resolve(
                                    a.applicationContext,item.page)
                            }
                            a.runOnUiThread{
                                if(a.isDestroyed||a.isFinishing)return@runOnUiThread
                                target.onSuccess{link->
                                    if(link.isNullOrBlank())
                                        status.text="No accessible torrent file in this release"
                                    else ResourceDownloads.route(a,link,
                                        if(area==TorrentArea.ADULT)"Videos" else "Games")
                                }.onFailure{status.text="Torrent unavailable: "+it.message}
                            }
                        }
                    },LinearLayout.LayoutParams(-2,-2).apply{leftMargin=ui.px(a,5)})
                    shell.addView(result,ui.lp(a,7))
                }
            }
            body.addView(shell,ui.lp(a,8))
        }
    }
}
