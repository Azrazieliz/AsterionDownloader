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
        val unique=all.filter{
            SearchExactness.matches(term,it.title) &&
                !IndexedMediaQuality.navigationTitle(it.title)
        }.distinctBy{SearchRanking.canonicalKey(it.url)}
        SearchResultsDb(ctx).use{db->
            db.save("SEARCH:"+mode+":"+term,items)
        }
        return Reply(unique,errors,checked+if(mode==0)1 else 0)
    }
    fun render(a:Activity,p:LinearLayout,refresh:()->Unit,
               addSource:()->Unit={}){
        p.addView(ui.text(a,"Search",23f,ui.white,true),ui.lp(a,14))
        p.addView(ui.text(a,"Find original media across connected sites.",
            12f,ui.secondary),ui.lp(a,4))

        val prefs=preferences(a)
        var selected=prefs.getInt("mode",0).coerceIn(0,modes.lastIndex)
        val picker=Spinner(a)
        picker.adapter=ArrayAdapter(a,android.R.layout.simple_spinner_dropdown_item,modes)
        picker.setSelection(selected)
        p.addView(picker,ui.lp(a,9))
        val box=ui.card(a)
        val field=CreatorNames.input(a,
            "Title, artist, creator URL, or site URL","ALL")
        field.setText(prefs.getString("query_"+selected,""))
        box.addView(field,ui.lp(a,0))
        val status=ui.text(a,"",12f,ui.secondary)
        val results=LinearLayout(a).apply{orientation=LinearLayout.VERTICAL}
        val siteFilters=LinearLayout(a).apply{orientation=LinearLayout.HORIZONTAL}
        val searchVersion=java.util.concurrent.atomic.AtomicLong()
        var searchWorker:Thread?=null
        val cancelSearch=ui.action(a,"STOP SEARCH",false){
            searchVersion.incrementAndGet()
            searchWorker?.interrupt()
            status.text="Search stopped · previous results retained"
        }.apply{visibility=android.view.View.GONE}
        fun draw(found:List<Found>){
            results.removeAllViews()
            siteFilters.removeAllViews()
            val available=listOf("ALL")+found.map{it.source}.distinct().sorted()
            val requested=prefs.getString("site_filter_"+selected,"ALL").orEmpty()
            val chosen=if(requested in available)requested else "ALL"
            if(available.size>2){
                for(name in available){
                    val chip=ui.chip(a,if(name=="ALL")"All sites" else name.take(22),
                        chosen==name){
                        prefs.edit().putString("site_filter_"+selected,name).apply()
                        draw(found)
                    }
                    siteFilters.addView(chip,LinearLayout.LayoutParams(-2,-2).apply{
                        rightMargin=ui.px(a,6)
                    })
                }
            }
            val filtered=found.filter{chosen=="ALL" || it.source==chosen}
                .filter{!IndexedMediaQuality.navigationTitle(it.title)}
                .take(75)
            fun saveHit(hit:Found){
                when(selected){
                    3->ResourceDownloads.route(a,hit.url,"Games")
                    4->ResourceDownloads.downloadFile(a,hit.url,"Books",
                        hit.filename.ifBlank{
                            CatalogDiscovery.safeFilename(hit.title,"epub")
                        })
                    else->{
                        val added=VideoDb(a).use{it.addVideo(hit.url,hit.title)}
                        if(added)VideoEngine.launch(a,false)
                        Toast.makeText(a,
                            if(added)"Queued in Downloads" else
                                "Already in Downloads · check its status",
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            val withPreviews=filtered.filter{SearchRanking.validUrl(it.thumbnail)}
            val withoutPreviews=filtered.filterNot{SearchRanking.validUrl(it.thumbnail)}
            // Result ownership is explicit. Never mix random thumbnails from
            // unrelated websites into a single apparently continuous gallery.
            val grouped=withPreviews.groupBy{
                it.source.removePrefix("Aloha · ").trim()
            }.toSortedMap()
            for((provider,media) in grouped){
                results.addView(ui.text(a,provider+" · "+media.size+" results",
                    14f,ui.white,true),ui.lp(a,9))
            for(chunk in media.chunked(2)){
                val row=ui.row(a).apply{gravity=android.view.Gravity.TOP}
                for(hit in chunk){
                    val tile=ui.card(a,7).apply{
                        background=ui.touch(a,ui.surface,14)
                        isClickable=true
                        isFocusable=true
                        contentDescription="Download "+hit.title
                        setOnClickListener{saveHit(hit)}
                    }
                    val preview=ImageView(a).apply{
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        contentDescription="Preview for "+hit.title
                    }
                    tile.addView(preview,LinearLayout.LayoutParams(
                        -1,ui.px(a,170)))
                    VideoSearchPreviews.display(a,preview,hit.thumbnail)
                    tile.addView(ui.text(a,hit.title.take(100),12f,ui.white,true)
                        .apply{
                            maxLines=2
                            ellipsize=android.text.TextUtils.TruncateAt.END
                        },ui.lp(a,7))
                    tile.addView(ui.text(a,hit.source.take(42),
                        11f,ui.secondary).apply{maxLines=1},ui.lp(a,4))
                    row.addView(tile,LinearLayout.LayoutParams(0,-2,1f)
                        .apply{rightMargin=ui.px(a,7)})
                }
                if(chunk.size==1)row.addView(android.view.View(a),
                    LinearLayout.LayoutParams(0,1,1f))
                results.addView(row,ui.lp(a,7))
            }
            }
            // No fake globe/card thumbnails: clearly identify unpreviewed
            // candidates as text-only results with a separate file action.
            val showTextOnly=prefs.getBoolean("show_text_results_"+selected,false)
            if(withoutPreviews.isNotEmpty()){
                results.addView(ui.action(a,
                    if(showTextOnly)"HIDE UNPREVIEWED RESULTS" else
                    "SHOW "+withoutPreviews.size+" UNPREVIEWED FILES",false){
                    prefs.edit().putBoolean("show_text_results_"+selected,
                        !showTextOnly).apply()
                    draw(found)
                },ui.lp(a,8))
            }
            if(showTextOnly)for(hit in withoutPreviews.take(30)){
                val row=ui.row(a).apply{
                    background=ui.touch(a,ui.surface,13)
                    setPadding(ui.px(a,10),ui.px(a,10),
                        ui.px(a,10),ui.px(a,10))
                }
                val content=LinearLayout(a).apply{
                    orientation=LinearLayout.VERTICAL
                }
                content.addView(ui.text(a,hit.title.take(110),13f,ui.white,true)
                    .apply{
                        maxLines=2
                        ellipsize=android.text.TextUtils.TruncateAt.END
                    })
                content.addView(ui.text(a,
                    hit.source.removePrefix("Aloha · ").take(45)+" · Preview unavailable",
                    11f,ui.secondary),ui.lp(a,3))
                row.addView(content,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(ui.action(a,"DOWNLOAD",false){saveHit(hit)},
                    LinearLayout.LayoutParams(-2,-2).apply{
                        leftMargin=ui.px(a,8)
                    })
                results.addView(row,ui.lp(a,6))
            }
            if(filtered.isEmpty())
                results.addView(ui.text(a,
                    "No matching originals indexed. Try another title or provider.",
                    13f,ui.secondary),ui.lp(a,13))
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
        val submit=ui.action(a,"SEARCH",true){
            val term=field.text.toString().trim()
            if(SearchRanking.validUrl(term) ||
                term.startsWith("magnet:?xt=urn:",true)){
                AsterionSmartAdd.enrollFromSearch(a,term,status){
                    status.text="Source saved to its module · select that module to manage Sync"
                }
                return@action
            }
            val selectedAtStart=selected
            if(term.length<2){status.text="Enter a specific title or name";return@action}
            val version=searchVersion.incrementAndGet()
            status.text="Searching "+modes[selectedAtStart]+
                " · previous results remain visible"
            cancelSearch.visibility=android.view.View.VISIBLE
            searchWorker=thread(name="asterion-federated-search"){
                val found=runCatching{search(a,term,selectedAtStart)}
                a.runOnUiThread{
                    if(a.isFinishing||a.isDestroyed ||
                        searchVersion.get()!=version)return@runOnUiThread
                    cancelSearch.visibility=android.view.View.GONE
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
        }
        field.addTextChangedListener(object:android.text.TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                submit.text=if(SearchRanking.validUrl(s.toString()) ||
                    s.toString().startsWith("magnet:?xt=urn:",true))
                    "ADD SOURCE" else "SEARCH"
            }
            override fun afterTextChanged(s:android.text.Editable?){}
        })
        box.addView(submit,ui.lp(a,8))
        box.addView(cancelSearch,ui.lp(a,4))
        box.addView(status,ui.lp(a,7))
        p.addView(box,ui.lp(a,9))
        p.addView(HorizontalScrollView(a).apply{
            isHorizontalScrollBarEnabled=false
            addView(siteFilters)
        },ui.lp(a,8))
        p.addView(results,ui.lp(a,9))
        draw(stored(a,selected))
    }
}
