package com.azrael.pixivdumpsync

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import java.net.URI

/** A single Kuroha-inspired, preview-first card grid shared by the native modules.
 * Filters operate locally on cached records; changing a filter never reloads a website.
 */
object UnifiedMediaGrid {
    private val ui=NocturneDesign
    private const val PREF="asterion_feed_filters"
    private enum class MediaFilter(val label:String){
        ALL("All"),IMAGES("Images"),VIDEO("Video"),DOWNLOADS("Files")
    }
    private fun match(item:IndexedItem,filter:MediaFilter)=when(filter){
        MediaFilter.ALL->true
        MediaFilter.IMAGES->item.kind=="image" ||
            (item.kind !in setOf("video","epub","model","archive","apk","torrent","game","original") &&
                item.preview!=null)
        MediaFilter.VIDEO->item.kind=="video"
        MediaFilter.DOWNLOADS->item.kind in setOf("epub","model","archive","apk","torrent","game","original")
    }
    private fun accent(areas:List<String>):Int=when{
        areas.any{it=="COSPLAY"||it=="ARTWORK"}->ui.cosplayRose
        areas.any{it=="BOOKS"||it=="COMICS"}->ui.booksGold
        areas.any{it=="GAMES"}->ui.gamesTeal
        areas.any{it=="ASSETS"}->ui.assetsLilac
        else->ui.primary
    }
    fun render(a:Activity,parent:LinearLayout,items:List<IndexedItem>,
               areas:List<String>,label:String,onClick:(IndexedItem)->Unit){
        val preference=a.getSharedPreferences(PREF,0)
        val key=areas.sorted().joinToString("_").take(90)
        val filterKey="filter_$key"
        val limitKey="limit_$key"
        val tint=accent(areas)
        val control=LinearLayout(a).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val region=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val active=MediaFilter.entries.find{it.name==preference.getString(filterKey,"ALL")}
            ?:MediaFilter.ALL
        val filters=HorizontalScrollView(a).apply{isHorizontalScrollBarEnabled=false}
        val chips=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        for(filter in MediaFilter.entries){
            val n=items.count{match(it,filter)}
            if(n==0 && filter!=MediaFilter.ALL)continue
            chips.addView(ui.chip(a,filter.label+" · "+n,active==filter){
                preference.edit().putString(filterKey,filter.name)
                    .putInt(limitKey,20).apply()
                parent.removeView(control);parent.removeView(region)
                render(a,parent,items,areas,label,onClick)
            },LinearLayout.LayoutParams(-2,-2).apply{rightMargin=ui.px(a,6)})
        }
        filters.addView(chips)
        control.addView(filters,LinearLayout.LayoutParams(0,-2,1f))
        val dense=a.getSharedPreferences("asterion_display",0)
            .getBoolean("compact",true)
        val columns=if(dense)3 else 2
        val coverHeight=if(ui.largeCovers(a))150 else if(columns==3)105 else 144
        val chosen=items.filter{match(it,active)}
        val limit=preference.getInt(limitKey,20).coerceIn(10,100)
        for(rowIndex in chosen.take(limit).chunked(columns)){
            val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
            for(item in rowIndex){
                val tile=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                    setPadding(ui.px(a,5),ui.px(a,5),ui.px(a,5),ui.px(a,7))
                    background=ui.touch(a,ui.raised,12)
                    isClickable=true;isFocusable=true
                    contentDescription=item.title+" · "+item.kind+" · Open item actions"
                    setOnClickListener{onClick(item)}
                    setOnLongClickListener{onClick(item);true}
                }
                if(ui.showPreviews(a)){
                    val cover=ImageView(a).apply{
                        // Character-card PNGs must remain uncropped and preserve
                        // their source proportions in the existing card frame.
                        scaleType=if(item.kind=="model" ||
                            item.source.contains("koikatsu",true) ||
                            item.source.contains("bepis.moe",true))
                            ImageView.ScaleType.FIT_CENTER
                            else ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(ui.surface)
                        contentDescription=item.title+" thumbnail"
                        setImageResource(when(item.kind){
                            "video"->R.drawable.ic_source_video
                            "epub","archive"->R.drawable.ic_source_toonily
                            "model"->R.drawable.ic_source_koikatsu
                            else->R.drawable.ic_source_site
                        })
                    }
                    tile.addView(cover,LinearLayout.LayoutParams(-1,ui.px(a,coverHeight)))
                    item.preview?.takeIf{SearchRanking.validUrl(it)}
                        ?.let{RemoteImageLoader.load(a,cover,it,item.source)}
                }
                tile.addView(ui.text(a,item.title,if(columns==3)11f else 12f,
                    ui.white,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},
                    ui.lp(a,6))
                val host=runCatching{URI(item.source).host.orEmpty().removePrefix("www.")}
                    .getOrDefault("")
                tile.addView(ui.text(a,host.take(26),10f,ui.secondary).apply{
                    maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
                },ui.lp(a,3))
                val metadata=when{
                    item.queued->"QUEUED"
                    item.kind in setOf("epub","torrent","model","archive")->item.kind.uppercase()
                    else->item.area.lowercase().replaceFirstChar{it.uppercase()}
                }
                tile.addView(ui.text(a,metadata,10f,tint,true).apply{maxLines=1},ui.lp(a,3))
                row.addView(tile,LinearLayout.LayoutParams(0,-2,1f).apply{
                    rightMargin=ui.px(a,5)
                })
            }
            repeat(columns-rowIndex.size){
                row.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
            }
            region.addView(row,ui.lp(a,7))
        }
        if(chosen.size>limit){
            region.addView(ui.action(a,"SHOW MORE · "+(chosen.size-limit),false){
                preference.edit().putInt(limitKey,(limit+20).coerceAtMost(100)).apply()
                parent.removeView(control);parent.removeView(region)
                render(a,parent,items,areas,label,onClick)
            },ui.lp(a,8))
        }
        if(chosen.isEmpty()){
            region.addView(ui.text(a,"No "+active.label.lowercase()+" indexed in this module yet.",
                12f,ui.secondary),ui.lp(a,12))
        }
        parent.addView(control,ui.lp(a,12))
        parent.addView(region,ui.lp(a,7))
    }
}
