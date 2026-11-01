package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** One visible, category-specific native torrent workflow across the main app. */
object TorrentSearchUi {
    fun render(a:Activity,body:LinearLayout,area:TorrentArea){
        val ui=NocturneDesign
        val name=when(area){TorrentArea.GAMES->"GAMES";TorrentArea.BOOKS->"BOOKS";TorrentArea.ADULT->"ADULT VIDEO"}
        val card=ui.card(a)
        card.addView(ui.text(a,"TORRENT DOWNLOADS · "+name,18f,ui.white,true),ui.lp(a,3))
        card.addView(ui.text(a,"Search by title or creator. Select a result, choose the files to transfer, then use the torrent queue below. Western index results come from Bitsearch and Pirate Bay; Sukebei covers Asian adult media.",12f,ui.secondary),ui.lp(a,7))
        val pref=a.getSharedPreferences("asterion_torrent_search",0)
        val field=EditText(a).apply{
            hint="Name / title / keywords"
            setSingleLine(true)
            setText(pref.getString(area.name+"_query",""))
            setTextColor(ui.white);setHintTextColor(ui.secondary)
            textSize=15f
        }
        card.addView(field,ui.lp(a,7))
        val status=ui.text(a,"Native torrent transfer · original files go to Downloads",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val records=ArrayList<CatalogHit>()
        fun draw(){
            results.removeAllViews()
            for(hit in records.take(80)){
                val item=ui.card(a)
                item.addView(ui.text(a,hit.title,15f,ui.white,true),ui.lp(a,3))
                item.addView(ui.text(a,hit.source+" · "+hit.details,12f,ui.secondary),ui.lp(a,5))
                item.addView(ui.action(a,"SELECT FILES & START TORRENT",true){
                    ResourceDownloads.route(a,hit.url,when(area){
                        TorrentArea.GAMES->"Games"
                        TorrentArea.BOOKS->"Books"
                        TorrentArea.ADULT->"Videos"
                    })
                },ui.lp(a,7))
                results.addView(item,ui.lp(a,7))
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
        card.addView(ui.action(a,"TORRENT QUEUE · PROGRESS / PAUSE / RESUME",false){
            TorrentTransfers.showQueue(a)
        },ui.lp(a,7))
        body.addView(card,ui.lp(a,12))
        body.addView(results,ui.lp(a,8))
    }
}
