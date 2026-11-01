package com.azrael.pixivdumpsync

import android.app.Activity
import android.widget.*
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.concurrent.thread

object GeneralVideoSearch {
    fun render(a:Activity,body:LinearLayout){
        val ui=NocturneDesign
        val frame=ui.card(a)
        frame.addView(ui.text(a,"GENERAL VIDEO KEYWORD SEARCH",18f,ui.white,true))
        frame.addView(ui.text(a,"Search the public federated PeerTube index. Adult site libraries remain in Porn & Hentai.",12f,ui.secondary))
        val field=EditText(a).apply{
            hint="Video title or creator"
            setSingleLine(true);setTextColor(ui.white);setHintTextColor(ui.secondary)
        }
        frame.addView(field,ui.lp(a,8))
        val status=ui.text(a,"",12f,ui.secondary)
        val items=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        frame.addView(ui.action(a,"SEARCH VIDEOS BY KEYWORD",true){
            val term=field.text.toString().trim()
            if(term.length<2){status.text="Enter a search";return@action}
            items.removeAllViews();status.text="Searching PeerTube catalog"
            thread(name="general-video-search"){
                val output=runCatching{
                    val link="https://sepiasearch.org/api/v1/search/videos?search="+
                        URLEncoder.encode(term,"UTF-8")+"&count=50"
                    val response=Jsoup.connect(link).ignoreContentType(true)
                        .timeout(18000).maxBodySize(2500000).execute().body()
                    JSONObject(response).optJSONArray("data")
                }
                a.runOnUiThread{
                    output.onSuccess{array->
                        status.text=(array?.length()?:0).toString()+" matches"
                        for(i in 0 until minOf(array?.length()?:0,35)){
                            val hit=array?.optJSONObject(i)?:continue
                            val url=hit.optString("url")
                            if(!SearchRanking.validUrl(url))continue
                            val tile=ui.card(a)
                            val image=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
                            tile.addView(image,LinearLayout.LayoutParams(-1,ui.px(a,145)))
                            hit.optString("thumbnailUrl").takeIf{SearchRanking.validUrl(it)}
                                ?.let{RemoteImageLoader.load(a,image,it,url)}
                            val title=hit.optString("name").ifBlank{"Video"}
                            tile.addView(ui.text(a,title,15f,ui.white,true),ui.lp(a,6))
                            tile.addView(ui.action(a,"DOWNLOAD VIA VIDEOFLOW",true){
                                if(VideoDb(a).use{it.addVideo(url,title)})VideoEngine.launch(a,false)
                                status.text="Queued or already present in VideoFlow downloads"
                            },ui.lp(a,5))
                            items.addView(tile,ui.lp(a,8))
                        }
                    }.onFailure{status.text="Source unavailable: "+it.message}
                }
            }
        },ui.lp(a,8))
        frame.addView(status,ui.lp(a,7))
        body.addView(frame,ui.lp(a,12));body.addView(items,ui.lp(a,7))
    }
}
