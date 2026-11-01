package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import com.asterion.videoflow.WebDiscovery
import org.jsoup.Jsoup
import java.net.URI
import kotlin.concurrent.thread

/** Name-based model index, selected-artist backfill and ongoing public-page monitoring. */
object WatchPornPanel {
    private data class Performer(val name:String,val url:String)
    private fun models(name:String):List<Performer>{
        val url="https://watchporn.to/models/"
        val doc=Jsoup.connect(url).timeout(18000).maxBodySize(3_000_000)
            .userAgent("Mozilla/5.0 (Android 17) Chrome/131.0").get()
        val tokens=name.trim().lowercase().split(Regex("\\s+")).filter{it.isNotBlank()}
        return doc.select("a[href*=/models/]").mapNotNull{node->
            val link=node.absUrl("href")
            val page=runCatching{URI(link)}.getOrNull()
            if(page?.host!="watchporn.to" || !page.path.matches(
                Regex("^/models/[a-zA-Z0-9_-]+/$")))return@mapNotNull null
            val title=node.text().trim().ifBlank{
                page.path.trim('/').substringAfter('/').replace('-',' ')
            }
            if(tokens.any{!title.contains(it,true)})return@mapNotNull null
            Performer(title,link)
        }.distinctBy{it.url}.take(40)
    }
    fun render(a:Activity,body:LinearLayout){
        val ui=NocturneDesign
        val block=ui.card(a)
        block.addView(ui.text(a,"WATCHPORN · ARTIST LIBRARY",18f,ui.white,true))
        block.addView(ui.text(a,"Search named performer profiles. Follow a selected profile for a first-page backfill and regular new-video checks. Downloads use the existing VideoFlow queue; inaccessible streams remain errors.",12f,ui.secondary),ui.lp(a,7))
        val input=EditText(a).apply{
            hint="Performer name"
            setSingleLine(true)
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        block.addView(input,ui.lp(a,8))
        val status=ui.text(a,"",12f,ui.secondary)
        val result=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        block.addView(ui.action(a,"SEARCH WATCHPORN ARTISTS",true){
            val q=input.text.toString().trim()
            if(q.length<2){status.text="Enter a performer name";return@action}
            status.text="Searching WatchPorn performers…";result.removeAllViews()
            thread(name="asterion-watchporn-models"){
                val found=runCatching{models(q)}
                a.runOnUiThread{
                    found.onSuccess{items->
                        status.text=items.size.toString()+" matching performer profiles"
                        for(item in items){
                            val entry=ui.card(a)
                            entry.addView(ui.text(a,item.name,15f,ui.white,true))
                            entry.addView(ui.text(a,item.url,11f,ui.secondary),ui.lp(a,5))
                            entry.addView(ui.action(a,"FOLLOW · BACKFILL / SYNC VIDEOS",true){
                                val saved=VideoDb(a).use{it.addWatch(item.url,item.name+" · WatchPorn")}
                                VideoEngine.launch(a,true)
                                Toast.makeText(a,if(saved)"Performer enrolled; backfill scan started"
                                    else "Performer already monitored; checking for new videos",Toast.LENGTH_LONG).show()
                            },ui.lp(a,8))
                            result.addView(entry,ui.lp(a,7))
                        }
                    }.onFailure{status.text="Artist search unavailable: "+it.message}
                }
            }
        },ui.lp(a,9))
        block.addView(status,ui.lp(a,7))
        block.addView(ui.action(a,"OPEN VIDEOFLOW · WATCHES / DOWNLOADS",false){
            a.startActivity(android.content.Intent(a,com.asterion.videoflow.MainActivity::class.java))
        },ui.lp(a,6))
        body.addView(block,ui.lp(a,13))
        body.addView(result,ui.lp(a,7))
    }
}
