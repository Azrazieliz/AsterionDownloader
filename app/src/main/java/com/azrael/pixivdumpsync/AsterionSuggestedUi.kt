package com.azrael.pixivdumpsync

import android.app.Activity
import android.net.Uri
import android.view.Gravity
import android.widget.*
import kotlin.concurrent.thread

/** A small home recommendation shelf. Only genuine media with thumbnails,
 * with related followed names ranked ahead of recent originals.
 */
object AsterionSuggestedUi {
    private val ui=NocturneDesign
    fun render(a:Activity,parent:LinearLayout){
        val heading=ui.card(a,11)
        heading.addView(ui.text(a,"From your sources",18f,ui.white,true))
        val info=ui.text(a,"Checking actual media…",12f,ui.secondary)
        heading.addView(info,ui.lp(a,4))
        val holder=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        heading.addView(holder,ui.lp(a,8))
        parent.addView(heading,ui.lp(a,13))
        thread(name="asterion-home-suggestions"){
            val data=runCatching{
                val prefs=a.getSharedPreferences("asterion_recommendations",0)
                    .getString("interests","").orEmpty().split('\n',',',';')
                val following=NameWatchDb(a).use{it.list().take(40).map{w->w.name}}
                val terms=(prefs+following).filter{it.length>=3}.distinct()
                SourceIndex.items(a,null,1500)
                    .filter{AsterionAdultHub.realMedia(it) &&
                        !it.preview.isNullOrBlank() &&
                        SearchRanking.validUrl(it.preview)}
                    .map{item->
                        val relevance=AsterionContentSuggestions.relevance(
                            terms,item.title).first
                        item to relevance
                    }
                    .filter{it.second>0}
                    .sortedWith(compareByDescending<Pair<IndexedItem,Int>>{it.second}
                        .thenByDescending{it.first.discoveredAt})
                    .distinctBy{SearchRanking.canonicalKey(it.first.url)}
                    .let{list->
                        val seen=HashMap<String,Int>()
                        list.filter{entry->
                            val source=SiteControls.key(entry.first.source)
                            val count=seen.getOrDefault(source,0)
                            if(count>=3)false else {seen[source]=count+1;true}
                        }
                    }.take(12)
            }
            a.runOnUiThread{
                if(a.isDestroyed||a.isFinishing||holder.parent!==heading)return@runOnUiThread
                data.onSuccess{rows->
                    if(rows.isEmpty()){
                        parent.removeView(heading)
                        return@onSuccess
                    }
                    info.text="Matched to followed models/interests · indexed, not downloaded"
                    val strip=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
                    for((item,_) in rows){
                        val tile=LinearLayout(a).apply{
                            orientation=LinearLayout.VERTICAL
                            background=ui.touch(a,ui.raised,11)
                            setPadding(ui.px(a,5),ui.px(a,5),
                                ui.px(a,5),ui.px(a,7))
                            isClickable=true
                            setOnClickListener{SourceIndexPanel.details(a,item)}
                        }
                        val cover=ImageView(a).apply{
                            scaleType=ImageView.ScaleType.CENTER_CROP
                            setImageResource(R.drawable.ic_source_site)
                        }
                        tile.addView(cover,LinearLayout.LayoutParams(-1,ui.px(a,116)))
                        item.preview?.let{RemoteImageLoader.load(a,cover,it,item.source)}
                        tile.addView(ui.text(a,item.title.take(60),11f,ui.white,true)
                            .apply{maxLines=2},ui.lp(a,6))
                        tile.addView(ui.text(a,
                            Uri.parse(item.source).host.orEmpty().removePrefix("www."),
                            10f,ui.secondary).apply{maxLines=1},ui.lp(a,4))
                        strip.addView(tile,LinearLayout.LayoutParams(ui.px(a,142),-2)
                            .apply{rightMargin=ui.px(a,7)})
                    }
                    holder.addView(HorizontalScrollView(a).apply{
                        isHorizontalScrollBarEnabled=false
                        addView(strip)
                    })
                }.onFailure{info.text="Media index unavailable: "+it.message}
            }
        }
    }
}
