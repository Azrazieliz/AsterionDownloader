package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import kotlin.concurrent.thread
import com.asterion.videoflow.VideoDb
import com.asterion.videoflow.VideoEngine

/** Exact content-type searches with downloadable-result previews, not site-launch buttons. */
object AsterionSearchHub {
    private val ui=NocturneDesign
    private val modes=listOf("All videos","Adult videos","Hentai videos","Games / visual novels","EPUB books")
    private data class Found(val title:String,val url:String,val source:String,
                             val thumbnail:String="",val filename:String="",val detail:String="")
    private data class Reply(val results:List<Found>,val errors:List<String>,val searched:Int)
    private fun preferences(a:Activity)=a.getSharedPreferences("asterion_unified_search",0)
    private fun stored(a:Activity,mode:Int):List<Found> = runCatching{
        val array=JSONArray(preferences(a).getString("results_"+mode,"[]"))
        (0 until minOf(100,array.length())).mapNotNull{i->
            val x=array.optJSONObject(i)?:return@mapNotNull null
            val url=x.optString("url")
            if(!SearchRanking.validUrl(url) && !url.startsWith("magnet:?"))return@mapNotNull null
            Found(x.optString("title"),url,x.optString("source"),x.optString("thumbnail"),
                x.optString("filename"),x.optString("detail"))
        }
    }.getOrDefault(emptyList())
    private fun save(a:Activity,mode:Int,term:String,results:List<Found>){
        val data=JSONArray()
        for(hit in results.take(100))data.put(JSONObject().apply{
            put("title",hit.title);put("url",hit.url);put("source",hit.source)
            put("thumbnail",hit.thumbnail);put("filename",hit.filename);put("detail",hit.detail)
        })
        preferences(a).edit().putString("results_"+mode,data.toString())
            .putString("query_"+mode,term).apply()
    }
    private fun peertube(term:String):List<Found> = runCatching{
        val url="https://sepiasearch.org/api/v1/search/videos?search="+
            URLEncoder.encode(term,"UTF-8")+"&count=30"
        val json=JSONObject(Jsoup.connect(url).ignoreContentType(true).timeout(18000)
            .maxBodySize(2500000).execute().body()).optJSONArray("data")
        (0 until minOf(json?.length()?:0,30)).mapNotNull{i->
            val hit=json?.optJSONObject(i)?:return@mapNotNull null
            val link=hit.optString("url")
            if(!SearchRanking.isVideoUrl(link))return@mapNotNull null
            Found(hit.optString("name"),link,"PeerTube",hit.optString("thumbnailUrl"))
        }
    }.getOrDefault(emptyList())
    private fun search(a:Activity,term:String,mode:Int):Reply {
        val ctx=a.applicationContext
        if(mode>=3){
            val catalog=CatalogDiscovery.search(term,mode==3)
            val results=if(mode==3)catalog.hits.filter{
                (it.kind==CatalogKind.TORRENT || it.kind==CatalogKind.GAME_ARCHIVE ||
                    it.kind==CatalogKind.APK) && !GameTorrentPolicy.clearlyNonGameTitle(it.title)
            }else catalog.hits.filter{it.kind==CatalogKind.EPUB}
            return Reply(results.map{Found(it.title,it.url,it.source,it.coverUrl,
                it.filename,it.details)},catalog.failures,catalog.searched)
        }
        val areas=when(mode){
            1->listOf(VideoSearchArea.PORN)
            2->listOf(VideoSearchArea.HENTAI)
            else->listOf(VideoSearchArea.PORN,VideoSearchArea.HENTAI)
        }
        val errors=ArrayList<String>();val items=ArrayList<RankedHit>()
        var checked=0
        for(area in areas){
            runCatching{KeywordSources.searchDetailed(ctx,term,area)}
                .onSuccess{items+=it.hits;errors+=it.failures;checked+=it.checked}
                .onFailure{errors+=area.name+": "+it.message}
        }
        val ranked=SearchRanking.deduplicate(term,items).map{
            Found(it.title,it.url,it.source,it.thumbnail.orEmpty(),"",it.description)
        }
        val all=if(mode==0)ranked+peertube(term) else ranked
        val unique=all.filter{SearchExactness.matches(term,it.title)}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
        SearchResultsDb(ctx).use{db->
            db.save("SEARCH:"+mode+":"+term,items)
        }
        return Reply(unique,errors,checked+if(mode==0)1 else 0)
    }
    fun render(a:Activity,p:LinearLayout,refresh:()->Unit){
        p.addView(ui.text(a,"Search",23f,ui.white,true),ui.lp(a,14))
        p.addView(ui.text(a,"Select the content type. Only matching files and media are shown.",
            12f,ui.secondary),ui.lp(a,4))
        val prefs=preferences(a)
        var selected=prefs.getInt("mode",0).coerceIn(0,modes.lastIndex)
        val picker=Spinner(a)
        picker.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,modes)
        picker.setSelection(selected)
        p.addView(picker,ui.lp(a,9))
        val box=ui.card(a)
        val field=EditText(a).apply{
            hint="Specific title, artist or keywords"
            setSingleLine(true);textSize=16f
            setTextColor(ui.white);setHintTextColor(ui.secondary)
            setText(prefs.getString("query_"+selected,""))
        }
        box.addView(field,ui.lp(a,0))
        val status=ui.text(a,"",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        fun draw(found:List<Found>){
            results.removeAllViews()
            for(hit in found.take(75)){
                val c=ui.card(a)
                if(SearchRanking.validUrl(hit.thumbnail)){
                    val preview=ImageView(a).apply{scaleType=ImageView.ScaleType.CENTER_CROP}
                    c.addView(preview,LinearLayout.LayoutParams(-1,ui.px(a,132)))
                    VideoSearchPreviews.display(a,preview,hit.thumbnail)
                }
                c.addView(ui.text(a,hit.title.take(160),15f,ui.white,true),ui.lp(a,0))
                c.addView(ui.text(a,hit.source,12f,ui.secondary),ui.lp(a,5))
                if(hit.detail.isNotBlank())c.addView(
                    ui.text(a,hit.detail.take(110),11f,ui.secondary),ui.lp(a,4))
                c.addView(ui.action(a,if(selected==4)"DOWNLOAD EPUB"
                    else if(selected==3)"VIEW GAME FILES"
                    else "DOWNLOAD VIDEO",true){
                    when(selected){
                        3->ResourceDownloads.route(a,hit.url,"Games")
                        4->ResourceDownloads.downloadFile(a,hit.url,"Books",
                            hit.filename.ifBlank{CatalogDiscovery.safeFilename(hit.title,"epub")})
                        else->{
                            if(VideoDb(a).use{it.addVideo(hit.url,hit.title)})
                                VideoEngine.launch(a,false)
                            Toast.makeText(a,"Video queued or already downloaded",Toast.LENGTH_SHORT).show()
                        }
                    }
                },ui.lp(a,8))
                results.addView(c,ui.lp(a,8))
            }
        }
        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,v:android.view.View?,index:Int,id:Long){
                if(selected==index)return
                selected=index;prefs.edit().putInt("mode",index).apply()
                field.setText(prefs.getString("query_"+index,""))
                draw(stored(a,index))
                status.text=""
            }
        }
        box.addView(ui.action(a,"SEARCH",true){
            val term=field.text.toString().trim()
            val selectedAtStart=selected
            if(term.length<2){status.text="Enter a specific title or name";return@action}
            status.text="Searching "+modes[selectedAtStart]+"…"
            results.removeAllViews()
            thread(name="asterion-federated-search"){
                val found=runCatching{search(a,term,selectedAtStart)}
                a.runOnUiThread{
                    if(a.isFinishing||a.isDestroyed)return@runOnUiThread
                    found.onSuccess{reply->
                        save(a,selectedAtStart,term,reply.results)
                        if(selected!=selectedAtStart)return@onSuccess
                        draw(reply.results)
                        status.text=reply.results.size.toString()+" results · "+
                            reply.searched+" providers"+
                            if(reply.errors.isEmpty())"" else
                                " · "+reply.errors.size+" provider errors"
                    }.onFailure{status.text=it.message?:"Search failed"}
                }
            }
        },ui.lp(a,8))
        box.addView(status,ui.lp(a,7))
        p.addView(box,ui.lp(a,9))
        p.addView(results,ui.lp(a,9))
        draw(stored(a,selected))
    }
}
