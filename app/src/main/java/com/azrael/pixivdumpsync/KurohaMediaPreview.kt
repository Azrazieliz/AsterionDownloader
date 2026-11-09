package com.azrael.pixivdumpsync

import android.app.Activity
import android.text.TextUtils
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Visual-first Kuroha/Pixiv gallery shared by individual artists/models/sites.
 * A tile always belongs to the owning card, is immediately visible, and
 * invokes an actual original-file download action, never a browser.
 */
internal object KurohaMediaPreview {
    private val ui=NocturneDesign
    data class Tile(
        val url:String,
        val title:String,
        val thumbnail:String,
        val attribution:String,
        val referer:String,
        val video:Boolean=false
    )
    fun render(
        a:Activity,
        parent:LinearLayout,
        group:String,
        tiles:List<Tile>,
        onClick:(Tile)->Unit
    ) {
        val unique=tiles.asSequence()
            .filter{SearchRanking.validUrl(it.thumbnail)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .take(120).toList()
        if(unique.isEmpty())return
        val pref=a.getSharedPreferences("asterion_kuroha_visual",0)
        val key="limit:"+group.take(120)
        val region=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val density=a.resources.displayMetrics.density
        val widthDp=a.resources.displayMetrics.widthPixels/density
        val columns=if(widthDp>=385f)4 else 3
        fun paint(){
            region.removeAllViews()
            val limit=pref.getInt(key,8).coerceIn(8,120)
            val shown=unique.take(limit)
            for(items in shown.chunked(columns)){
                val row=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
                for(tile in items){
                    val card=LinearLayout(a).apply{
                        orientation=LinearLayout.VERTICAL
                        background=ui.touch(a,ui.raised,10)
                        setPadding(ui.px(a,3),ui.px(a,3),ui.px(a,3),ui.px(a,6))
                        isClickable=true
                        isFocusable=true
                        contentDescription="Download "+tile.title+" · "+tile.attribution
                        setOnClickListener{onClick(tile)}
                    }
                    val cover=ImageView(a).apply{
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        setBackgroundColor(ui.soft)
                        contentDescription=tile.title+" thumbnail"
                    }
                    card.addView(cover,LinearLayout.LayoutParams(
                        -1,ui.px(a,if(columns==4)112 else 148)))
                    if(tile.video)VideoSearchPreviews.display(a,cover,tile.thumbnail)
                    else RemoteImageLoader.load(a,cover,tile.thumbnail,tile.referer)
                    card.addView(ui.text(a,tile.title,
                        if(columns==4)10f else 11f,ui.white,true).apply{
                        maxLines=2
                        ellipsize=TextUtils.TruncateAt.END
                        minHeight=ui.px(a,31)
                    },ui.lp(a,5))
                    card.addView(ui.text(a,tile.attribution,10f,ui.secondary).apply{
                        maxLines=1
                        ellipsize=TextUtils.TruncateAt.END
                    },ui.lp(a,3))
                    row.addView(card,LinearLayout.LayoutParams(0,-2,1f).apply{
                        rightMargin=ui.px(a,4)
                    })
                }
                repeat(columns-items.size){
                    row.addView(View(a),LinearLayout.LayoutParams(0,1,1f))
                }
                region.addView(row,ui.lp(a,6))
            }
            if(unique.size>limit){
                region.addView(ui.action(a,
                    "SHOW MORE PREVIEWS · "+(unique.size-limit),false){
                    pref.edit().putInt(key,(limit+8).coerceAtMost(120)).apply()
                    paint()
                },ui.lp(a,6))
            }
            if(limit>8){
                region.addView(ui.action(a,"SHOW FEWER",false){
                    pref.edit().putInt(key,8).apply()
                    paint()
                },ui.lp(a,5))
            }
        }
        paint()
        parent.addView(region,ui.lp(a,7))
    }
}
