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

/** SepiaSearch returns genuine PeerTube watch pages, whose short /w/<id>
 * routes are not covered by generic video-host URL heuristics. Only apply
 * this exception to the independent PeerTube search API results.
 */
internal object PeerTubeResultPolicy {
    fun isVideoWatch(raw:String):Boolean {
        if(!SearchRanking.validUrl(raw) ||
            ProviderRetirement.isRetired(raw))return false
        val uri=runCatching{java.net.URI(raw)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.port!=-1 ||
            uri.fragment!=null || !uri.rawQuery.isNullOrEmpty())return false
        // SepiaSearch is a PeerTube index; return only PeerTube's exact
        // selected-watch routes. The generic "/videos" heuristic also
        // admits category/listing pages and must not classify them as media.
        return Regex("""^/(?:w|videos/watch)/[A-Za-z0-9_-]{10,100}/?$""")
            .matches(uri.path.orEmpty())
    }
}

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
        (0 until minOf(1200,array.length())).mapNotNull{i->
            val x=array.optJSONObject(i)?:return@mapNotNull null
            val url=x.optString("url")
            if(!SearchRanking.validUrl(url) && !url.startsWith("magnet:?"))return@mapNotNull null
            Found(x.optString("title"),url,x.optString("source"),x.optString("thumbnail"),
                x.optString("filename"),x.optString("detail"))
        }
    }.getOrDefault(emptyList())
    private fun save(a:Activity,mode:Int,term:String,results:List<Found>){
        val data=JSONArray()
        for(hit in results.take(1200))data.put(JSONObject().apply{
            put("title",hit.title);put("url",hit.url);put("source",hit.source)
            put("thumbnail",hit.thumbnail);put("filename",hit.filename);put("detail",hit.detail)
        })
        preferences(a).edit().putString("results_"+mode,data.toString())
            .putString("query_"+mode,term).apply()
    }
    private fun peertube(term:String):List<Found> {
        val url="https://sepiasearch.org/api/v1/search/videos?search="+
            URLEncoder.encode(term,"UTF-8")+"&count=30"
        val json=JSONObject(Jsoup.connect(url).ignoreContentType(true).timeout(18000)
            .maxBodySize(2500000).execute().body()).optJSONArray("data")
        return (0 until minOf(json?.length()?:0,30)).mapNotNull{i->
            val hit=json?.optJSONObject(i)?:return@mapNotNull null
            val link=hit.optString("url")
            if(!PeerTubeResultPolicy.isVideoWatch(link))return@mapNotNull null
            Found(hit.optString("name"),link,"PeerTube",hit.optString("thumbnailUrl"))
        }
    }
    private fun search(a:Activity,term:String,mode:Int,indexPage:Int=0):Reply {
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
            // All videos includes independent general internet results.
            // Adult/Hentai enrich but never gate the web-index search.
            else->listOf(VideoSearchArea.GENERAL,
                VideoSearchArea.PORN,VideoSearchArea.HENTAI)
        }
        val errors=ArrayList<String>();val items=ArrayList<RankedHit>()
        var checked=0
        // PeerTube is a separate general-web source. Never add its possible
        // 18-second socket wait AFTER the main 32-second search deadline.
        val sharedDeadline=System.nanoTime()+
            java.util.concurrent.TimeUnit.SECONDS.toNanos(32)
        val workers=java.util.concurrent.Executors.newFixedThreadPool(
            areas.size+if(mode==0 && indexPage==0)1 else 0)
        var peerFuture:java.util.concurrent.Future<List<Found>>?=null
        var peerResults:List<Found> = emptyList()
        var peerCompleted=0
        try{
            if(mode==0 && indexPage==0)
                peerFuture=workers.submit(java.util.concurrent.Callable{
                    peertube(term)
                })
            val completion=java.util.concurrent.ExecutorCompletionService<
                Triple<VideoSearchArea,SearchOutcome?,String?>>(workers)
            for(area in areas)completion.submit(java.util.concurrent.Callable{
                runCatching{KeywordSources.searchDetailed(ctx,term,area,indexPage)}
                    .fold({Triple(area,it,null)},{
                        Triple(area,null,(it.message?:it.javaClass.simpleName).take(130))
                    })
            })
            // One overall 35s budget for combined category search. Waiting
            // 35s per future in order could stall All for 70 seconds and
            // discard the fast category when another provider was blocked.
            var finished=0
            while(finished<areas.size){
                val remaining=sharedDeadline-System.nanoTime()
                if(remaining<=0L)break
                val ready=completion.poll(remaining,
                    java.util.concurrent.TimeUnit.NANOSECONDS)?:break
                finished++
                val (area,outcome,error)=ready.get()
                if(outcome!=null){
                    items+=outcome.hits
                    errors+=outcome.failures
                    checked+=outcome.checked
                }
                if(error!=null)errors+=area.name+": "+error
            }
            if(finished<areas.size)
                errors+=(areas.size-finished).toString()+
                    " search categories exceeded the combined deadline"
            peerFuture?.let{future->
                val remaining=(sharedDeadline-System.nanoTime())
                    .coerceAtLeast(0L)
                try{
                    peerResults=future.get(remaining,
                        java.util.concurrent.TimeUnit.NANOSECONDS)
                    peerCompleted=1
                }catch(ex:java.util.concurrent.TimeoutException){
                    errors+="PeerTube exceeded the shared web deadline"
                }catch(ex:Exception){
                    // An inaccessible peer index is an unavailable source,
                    // not a successful empty search or completed route.
                    val cause=(ex as? java.util.concurrent.ExecutionException)
                        ?.cause?:ex
                    errors+="PeerTube: "+(cause.message?:cause.javaClass.simpleName)
                        .take(130)
                }
            }
        }finally{
            workers.shutdownNow()
        }
        val ranked=SearchRanking.deduplicateDiscovery(term,items).map{
            Found(it.title,it.url,it.source,it.thumbnail.orEmpty(),"",it.description)
        }
        val all=ranked+peerResults
        // Names are ordered identities: "Bella Alice" must not include
        // "Barbara Bella and Alice Marshall" merely because both tokens
        // appeared in unrelated parts of a title. Descriptive torrent/game
        // searches keep their existing broader word matching elsewhere.
        val unique=all.filter{
            SearchExactness.mediaMatches(term,it.title) &&
                !IndexedMediaQuality.navigationTitle(it.title)
        }.distinctBy{SearchRanking.canonicalKey(it.url)}
         .sortedWith(compareByDescending<Found>{
             SearchExactness.matchesPhrase(term,it.title)
         })
        if(indexPage==0)SearchResultsDb(ctx).use{db->
            db.save("SEARCH:"+mode+":"+term,items)
        }
        return Reply(unique,errors,checked+peerCompleted)
    }
    fun render(a:Activity,p:LinearLayout,refresh:()->Unit,
               addSource:()->Unit={}){
        p.addView(ui.text(a,"Search",23f,ui.white,true),ui.lp(a,14))
        p.addView(ui.text(a,
            "Searches the public indexed web, including sites not configured "+
                "as providers. Search hits are candidates, not verified originals.",
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
            val matching=found.filter{
                (chosen=="ALL" || it.source==chosen) &&
                    !IndexedMediaQuality.navigationTitle(it.title)
            }
            // Mixed discovery is not a single website's gallery.
            // Round-robin actual independent publishers and keep original
            // provider identity. Never invent a result to fill empty sites.
            val bySource=matching.groupBy{it.source}.values.map{it.iterator()}
            val filtered=ArrayList<Found>()
            val visibleLimit=prefs.getInt("visible_result_count_"+selected,75)
                .coerceAtLeast(25)
            while(filtered.size<visibleLimit){
                var advanced=false
                for(cursor in bySource){
                    if(cursor.hasNext() && filtered.size<visibleLimit){
                        filtered+=cursor.next()
                        advanced=true
                    }
                }
                if(!advanced)break
            }
            fun saveHit(hit:Found){
                when(selected){
                    3->ResourceDownloads.route(a,hit.url,"Games")
                    4->ResourceDownloads.downloadFile(a,hit.url,"Books",
                        hit.filename.ifBlank{
                            CatalogDiscovery.safeFilename(hit.title,"epub")
                        })
                    else->{
                        // A HentaiHaven /watch/<series> landing page is NOT
                        // a file. Show its publisher's actual episode list,
                        // rather than queuing a doomed series HTML response.
                        if(com.asterion.videoflow.HentaiHavenEpisodeSource
                                .isSeries(hit.url)){
                            SelectedVideoRouter.request(a,hit.url,hit.title,"HENTAI")
                        }else{
                            val added=VideoDb(a).use{
                                it.addVideo(hit.url,hit.title,origin="user")
                            }
                            if(added)VideoEngine.launch(a,false)
                            Toast.makeText(a,
                                if(added)"Resolving selected original in Downloads"
                                else "Already in Downloads · check its status",
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            // Previously all sources lacking pre-indexed thumbnail URLs
            // were relegated to text rows: the exact selected-page publisher
            // poster resolver was NEVER invoked. This made video search look
            // like empty placeholders even for pages publishing real OG art.
            // Attempt a bounded set of exact-page posters asynchronously;
            // keep the remainder available as text, without inventing art
            // or loading hundreds of player pages at once.
            val indexed=filtered.filter{SearchRanking.validUrl(it.thumbnail)}
            val missing=filtered.filterNot{SearchRanking.validUrl(it.thumbnail)}
            val withPreviews=(indexed+missing.take(30))
                .distinctBy{SearchRanking.canonicalKey(it.url)}
            val withoutPreviews=missing.drop(30)
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
                    VideoSearchPreviews.display(a,preview,hit.thumbnail,hit.url)
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
            // Unpreviewed genuine publisher links are useful results too.
            // Previously they were hidden by default, leaving the impression
            // that every result came from the one site with thumbnail URLs.
            val showTextOnly=prefs.getBoolean("show_text_results_"+selected,true)
            if(withoutPreviews.isNotEmpty()){
                results.addView(ui.action(a,
                    if(showTextOnly)"HIDE UNPREVIEWED RESULTS" else
                    "SHOW "+withoutPreviews.size+" UNPREVIEWED FILES",false){
                    prefs.edit().putBoolean("show_text_results_"+selected,
                        !showTextOnly).apply()
                    draw(found)
                },ui.lp(a,8))
            }
            if(showTextOnly)for(hit in withoutPreviews){
                val row=ui.row(a).apply{
                    background=ui.touch(a,ui.surface,13)
                    setPadding(ui.px(a,10),ui.px(a,10),
                        ui.px(a,10),ui.px(a,10))
                }
                // A search index lacking a poster is not proof the
                // SELECTED publisher page lacks one. Recover only that
                // page's genuine og:image/video[poster] asynchronously,
                // without mixing images from another search hit.
                if(com.asterion.videoflow.VideoSiteProfiles.isVideoPage(hit.url)){
                    val poster=ImageView(a).apply{
                        scaleType=ImageView.ScaleType.CENTER_CROP
                        contentDescription="Publisher poster for "+hit.title
                    }
                    row.addView(poster,LinearLayout.LayoutParams(
                        ui.px(a,82),ui.px(a,82)).apply{
                            rightMargin=ui.px(a,8)
                        })
                    VideoSearchPreviews.display(a,poster,null,hit.url)
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
                    hit.source.removePrefix("Aloha · ").take(45)+
                        " · Publisher poster checked only on selected page",
                    11f,ui.secondary),ui.lp(a,3))
                row.addView(content,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(ui.action(a,"DOWNLOAD",false){saveHit(hit)},
                    LinearLayout.LayoutParams(-2,-2).apply{
                        leftMargin=ui.px(a,8)
                    })
                results.addView(row,ui.lp(a,6))
            }
            if(filtered.size<matching.size) {
                results.addView(ui.action(a,
                    "SHOW MORE RESULTS · "+(matching.size-filtered.size)+" REMAINING",false){
                    prefs.edit().putInt("visible_result_count_"+selected,
                        visibleLimit+50).apply()
                    draw(found)
                },ui.lp(a,9))
            }
            if(selected<=2 && prefs.getString("query_"+selected,"")
                    .orEmpty().isNotBlank()){
                // A display-only SHOW MORE button reuses the SAME 50 index
                // hits. This runs an actual second internet search with Bing
                // and DuckDuckGo page offsets, and appends unique publisher
                // pages without requiring any configured provider.
                results.addView(ui.action(a,"SEARCH NEXT WEB PAGE",false){
                    val requestedMode=selected
                    val requestedTerm=prefs.getString(
                        "query_"+requestedMode,"").orEmpty().trim()
                    if(requestedTerm.isBlank())return@action
                    val nextPage=prefs.getInt(
                        "index_page_"+requestedMode,0).coerceAtLeast(0)+1
                    val version=searchVersion.incrementAndGet()
                    status.text="Searching more of the internet · page "+
                        (nextPage+1)+" (original results preserved)"
                    cancelSearch.visibility=android.view.View.VISIBLE
                    searchWorker=thread(name="asterion-web-search-next-page"){
                        val answer=runCatching{
                            search(a,requestedTerm,requestedMode,nextPage)
                        }
                        a.runOnUiThread{
                            if(a.isFinishing || a.isDestroyed ||
                                searchVersion.get()!=version)return@runOnUiThread
                            cancelSearch.visibility=android.view.View.GONE
                            answer.onSuccess{reply->
                                val previous=stored(a,requestedMode)
                                val oldKeys=previous.map{
                                    SearchRanking.canonicalKey(it.url)
                                }.toHashSet()
                                val fresh=reply.results.filter{
                                    oldKeys.add(SearchRanking.canonicalKey(it.url))
                                }
                                val combined=(previous+fresh).take(1200)
                                save(a,requestedMode,requestedTerm,combined)
                                prefs.edit().putInt("index_page_"+requestedMode,
                                    nextPage).apply()
                                if(selected==requestedMode)draw(combined)
                                status.text=fresh.size.toString()+
                                    " new results from web page "+(nextPage+1)+
                                    " · "+combined.size+" retained"+
                                    if(reply.errors.isEmpty())"" else
                                        " · "+reply.errors.size+
                                        " index routes unavailable"
                            }.onFailure{
                                status.text="Internet paging failed: "+
                                    it.message.orEmpty().take(160)
                            }
                        }
                    }
                },ui.lp(a,9))
            }
            if(filtered.isEmpty())
                results.addView(ui.text(a,
                    "No matching pages found in this search. Try a different title.",
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
                        prefs.edit().putInt(
                            "index_page_"+selectedAtStart,0).apply()
                        if(selected!=selectedAtStart)return@onSuccess
                        draw(reply.results)
                        status.text=reply.results.size.toString()+" matching web results · "+
                            reply.searched+" completed search routes"+
                            if(reply.errors.isEmpty())"" else
                                "\nUnavailable sources ("+reply.errors.size+"): "+
                                    reply.errors.take(3).joinToString("; ")
                                        .take(350)
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
