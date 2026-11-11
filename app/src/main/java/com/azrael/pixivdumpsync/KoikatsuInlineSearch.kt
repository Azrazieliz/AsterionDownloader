package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.*
import kotlin.concurrent.thread

/** Search both original Koikatsu card databases directly inside Assets. */
object KoikatsuInlineSearch {
    fun render(a:Activity,parent:LinearLayout){
        val ui=NocturneDesign
        val card=ui.card(a)
        card.addView(ui.text(a,"Koikatsu character cards",18f,ui.white,true))
        val input=EditText(a).apply{
            hint="Character name"
            setSingleLine(true);textSize=15f
            setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        card.addView(input,ui.lp(a,7))
        val status=ui.text(a,"BepisDB · KoikatsuCards",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        card.addView(ui.action(a,"SEARCH CHARACTERS",true){
            val query=input.text.toString().trim()
            if(query.length<2){status.text="Enter a character name";return@action}
            status.text="Searching card databases…"
            results.removeAllViews()
            thread(name="asterion-koikatsu-inline-search"){
                val response=runCatching{KoikatsuCards.search(query)}
                a.runOnUiThread{
                    if(a.isDestroyed||a.isFinishing)return@runOnUiThread
                    response.onSuccess{(items,errors)->
                        status.text=items.size.toString()+" matching cards · "+
                            errors.size+" source errors"
                        // A compact two-column media gallery, not a repeated
                        // sequence of giant buttons for variants of one character.
                        for(group in items.take(60).chunked(2)){
                            val line=ui.row(a).apply{gravity=android.view.Gravity.TOP}
                            for(item in group){
                                val tile=ui.card(a,7).apply{
                                    background=ui.touch(a,ui.surface,14)
                                    isClickable=true
                                    isFocusable=true
                                    contentDescription=item.name+" · "+item.source+
                                        " · download original PNG"
                                    setOnClickListener{
                                        status.text="Downloading original PNG for "+item.name+"…"
                                        thread(name="asterion-koikatsu-inline-save"){
                                            val result=runCatching{
                                                KoikatsuCards.download(a,
                                                    KoikatsuCards.resolve(item),item.name)
                                            }
                                            a.runOnUiThread{
                                                if(!a.isFinishing && !a.isDestroyed)
                                                    status.text=result.fold(
                                                        {"Saved original PNG · "+item.name},
                                                        {"PNG download failed: "+it.message})
                                            }
                                        }
                                    }
                                }
                                val image=ImageView(a).apply{
                                    scaleType=ImageView.ScaleType.FIT_CENTER
                                    adjustViewBounds=true
                                    setImageResource(R.drawable.ic_source_koikatsu)
                                    contentDescription=item.name+" preview"
                                }
                                tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,156)))
                                val cover=item.preview
                                    ?:item.download.takeIf{item.source=="BepisDB"}
                                if(!cover.isNullOrBlank())
                                    RemoteImageLoader.load(a,image,cover,item.page)
                                tile.addView(ui.text(a,item.name,13f,ui.white,true).apply{
                                    maxLines=2
                                    ellipsize=android.text.TextUtils.TruncateAt.END
                                },ui.lp(a,6))
                                tile.addView(ui.text(a,
                                    item.source+if(item.author.isNotBlank())
                                        " · "+item.author else "",
                                    10f,ui.secondary).apply{maxLines=1},ui.lp(a,4))
                                line.addView(tile,LinearLayout.LayoutParams(0,-2,1f)
                                    .apply{rightMargin=ui.px(a,7)})
                            }
                            if(group.size==1)line.addView(android.view.View(a),
                                LinearLayout.LayoutParams(0,1,1f))
                            results.addView(line,ui.lp(a,7))
                        }
                    }.onFailure{status.text="Card search failed: "+it.message}
                }
            }
        },ui.lp(a,7))
        card.addView(status,ui.lp(a,6))
        parent.addView(card,ui.lp(a,12))
        parent.addView(results,ui.lp(a,7))
    }
}
