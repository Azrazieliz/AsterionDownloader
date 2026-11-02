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
                        for(item in items.take(45)){
                            val row=ui.card(a,12)
                            val image=ImageView(a).apply{
                                scaleType=ImageView.ScaleType.CENTER_CROP
                                setImageResource(R.drawable.ic_source_koikatsu)
                            }
                            row.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,120)))
                            val cover=item.preview?:if(item.source=="BepisDB")item.download else null
                            if(cover!=null)RemoteImageLoader.load(a,image,cover,item.page)
                            row.addView(ui.text(a,item.name,15f,ui.white,true),ui.lp(a,6))
                            row.addView(ui.text(a,item.source,12f,ui.secondary),ui.lp(a,3))
                            row.addView(ui.action(a,"DOWNLOAD PNG CARD",true){
                                thread(name="asterion-koikatsu-inline-save"){
                                    val result=runCatching{
                                        KoikatsuCards.download(a,KoikatsuCards.resolve(item),item.name)
                                    }
                                    a.runOnUiThread{
                                        status.text=result.fold(
                                            {"Saved "+item.name},{"Card download failed: "+it.message})
                                    }
                                }
                            },ui.lp(a,7))
                            results.addView(row,ui.lp(a,8))
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
