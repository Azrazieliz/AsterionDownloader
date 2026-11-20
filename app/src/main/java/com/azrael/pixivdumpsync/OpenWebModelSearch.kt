package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Searches independent PUBLIC web indexes for downloadable original 3D model
 * files, not just the two configured asset libraries. These are search
 * candidates, never proof of a complete/downloadable original. No new site
 * subscription or paid marketplace is added, and result pages/ads are not
 * disguised as model downloads.
 */
internal object OpenWebModelSearch {
    data class Candidate(val title:String,val url:String,val format:String,
                         val host:String,val thumbnail:String?=null,
                         val referer:String?=null)

    private val modelExtensions=setOf(
        "blend","fbx","obj","stl","gltf","glb","dae",
        "usd","usdz","abc","ply","3mf","x3d","unitypackage",
        "vrm","pmx")
    private fun extension(url:String):String? = runCatching{
        val path=URI(url).path.orEmpty()
        path.substringAfterLast('/').substringAfterLast('.',missingDelimiterValue="")
            .lowercase(Locale.ROOT).takeIf{it in modelExtensions}
    }.getOrNull()

    internal fun parseWebIndex(html:String,base:String,engine:String):List<Candidate> {
        val doc=Jsoup.parse(html,base)
        val anchors=if(engine=="DuckDuckGo")
            doc.select("a.result__a[href]")
        else doc.select("li.b_algo h2 a[href]")
        val discovered=LinkedHashMap<String,Candidate>()
        for(anchor in anchors.take(120)){
            val raw=anchor.absUrl("href").ifBlank{anchor.attr("href")}
            val initial=runCatching{URI(raw)}.getOrNull()?:continue
            val url=if(initial.host.orEmpty().endsWith("duckduckgo.com") &&
                initial.path=="/l/") {
                initial.rawQuery.orEmpty().split('&').firstOrNull{
                    it.startsWith("uddg=")
                }?.substringAfter('=')?.let{
                    runCatching{URLDecoder.decode(it,"UTF-8")}.getOrNull()
                }.orEmpty()
            }else raw
            val uri=runCatching{URI(url)}.getOrNull()?:continue
            val ext=extension(url)?:continue
            if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
                uri.rawFragment!=null || !SearchRanking.validUrl(url) ||
                ProviderRetirement.isRetired(url))continue
            val host=uri.host.orEmpty().lowercase(Locale.ROOT)
            if(host.isBlank())continue
            val name=anchor.text().trim().take(180)
                .ifBlank{uri.path.substringAfterLast('/').take(120)}
            if(name.isBlank() || IndexedMediaQuality.navigationTitle(name))continue
            discovered.putIfAbsent(SearchRanking.canonicalKey(url),
                Candidate(name,url,ext.uppercase(Locale.ROOT),host))
            if(discovered.size>=90)break
        }
        return discovered.values.toList()
    }


    /** Public first-party JSON search results, not fabricated model files.
     * The federated endpoint reports per-provider download eligibility;
     * only free downloadable GLB entries are offered as originals.
     */
    internal fun parseFederatedModels(json:String,term:String):List<Candidate>{
        val array=JSONObject(json).optJSONArray("results")?:return emptyList()
        val out=LinkedHashMap<String,Candidate>()
        for(i in 0 until minOf(200,array.length())){
            val entry=array.optJSONObject(i)?:continue
            if(!entry.optBoolean("downloadable") ||
                !entry.optJSONObject("price")?.optBoolean("free").orFalse())
                continue
            val formats=entry.optJSONArray("formats")?:continue
            if((0 until formats.length()).none{
                formats.optString(it).equals("glb",true)})continue
            val title=entry.optString("title").trim().take(160)
            val id=entry.optString("id")
            if(!id.matches(Regex("[A-Za-z0-9_-]{2,45}:[A-Za-z0-9._-]{3,180}")) ||
                !SearchExactness.matches(term,title+" "+
                    entry.optJSONArray("tags").toString()))continue
            val page=entry.optString("url").takeIf{SearchRanking.validUrl(it)}
            val encoded=URLEncoder.encode(id,"UTF-8")
            val url="https://3d.shep.bot/v1/assets/"+encoded+"/download?format=glb"
            val photo=entry.optString("thumbnailUrl").takeIf{
                SearchRanking.validUrl(it)}
            val provider=entry.optString("provider").take(45).ifBlank{"federated"}
            out.putIfAbsent(SearchRanking.canonicalKey(url),
                Candidate(title,url,"GLB","3d.shep.bot / "+provider,
                    photo,page))
            if(out.size>=90)break
        }
        return out.values.toList()
    }
    private fun Boolean?.orFalse():Boolean=this==true

    /** An independent public CC0 library with declared actual file URLs.
     * A landing page or preview GLB is NEVER substituted for a full file.
     */
    internal fun parseGrab3DModels(json:String,term:String):List<Candidate>{
        val array=JSONObject(json).optJSONArray("models")?:return emptyList()
        val out=LinkedHashMap<String,Candidate>()
        for(i in 0 until minOf(6000,array.length())){
            val item=array.optJSONObject(i)?:continue
            val name=item.optJSONObject("name")?.optString("en")
                .orEmpty().trim().take(160)
            val slug=item.optString("slug")
            if(!slug.matches(Regex("[A-Za-z0-9_-]{2,180}")) ||
                !SearchExactness.matches(term,name+" "+
                    item.optJSONArray("tags").toString()))continue
            val files=item.optJSONArray("files")?:continue
            val file=(0 until files.length()).mapNotNull{
                files.optJSONObject(it)
            }.firstOrNull{
                it.optString("format").equals("glb",true) &&
                    it.optString("variant").equals("full",true) &&
                    runCatching {
                        val address=URI(it.optString("url"))
                        address.scheme=="https" &&
                            address.host.orEmpty().lowercase(Locale.ROOT) in
                                setOf("grab3d.com","www.grab3d.com") &&
                            address.path.orEmpty().endsWith(".glb",true)
                    }.getOrDefault(false)
            }?:continue
            val url=file.optString("url")
            val poster=item.optJSONObject("media")?.optString("thumbnail")
                ?.takeIf{SearchRanking.validUrl(it)}
            out.putIfAbsent(SearchRanking.canonicalKey(url),
                Candidate(name,url,"GLB","grab3d.com",
                    poster,"https://grab3d.com/models/"+slug))
            if(out.size>=90)break
        }
        return out.values.toList()
    }

    private fun publicJson(url:String,maxSize:Int):String{
        val response=Jsoup.connect(url)
            .userAgent("AsterionDownloader/1.3 Android")
            .timeout(11_000).maxBodySize(maxSize)
            .ignoreContentType(true).ignoreHttpErrors(true)
            .followRedirects(false).execute()
        require(response.statusCode() in 200..299){
            "Public model index HTTP "+response.statusCode()
        }
        return response.body()
    }
    private fun federated(term:String):List<Candidate> =
        parseFederatedModels(publicJson(
            "https://3d.shep.bot/v1/search?q="+
                URLEncoder.encode(term,"UTF-8")+
                "&type=model&free=true&downloadable=true&limit=90",2_500_000),
            term)

    // Published catalog is a multi-megabyte immutable JSON snapshot;
    // cache it rather than redownloading ~2,500 records on every query.
    @Volatile private var cachedCatalog:Pair<Long,String>?=null
    private fun grab3d(term:String):List<Candidate>{
        val now=System.currentTimeMillis()
        val existing=cachedCatalog
        val json=if(existing!=null && now-existing.first<12L*3600_000L)
            existing.second else synchronized(this){
                val recent=cachedCatalog
                if(recent!=null && now-recent.first<12L*3600_000L)
                    recent.second
                else publicJson("https://grab3d.com/api/v1/models.json",12_000_000)
                    .also{cachedCatalog=System.currentTimeMillis() to it}
            }
        return parseGrab3DModels(json,term)
    }

    private fun searchOne(term:String,engine:String,mode:Int):List<Candidate> {
        val phrase=if(mode==0)
            term.trim()+" 3d model filetype:glb OR filetype:fbx OR filetype:obj OR filetype:blend"
        else term.trim()+" free 3d model download original glb fbx obj"
        val q=URLEncoder.encode(phrase,"UTF-8")
        val target=if(engine=="DuckDuckGo")
            "https://html.duckduckgo.com/html/?q="+q
        else "https://www.bing.com/search?q="+q+"&count=50"
        val response=Jsoup.connect(target)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
            .timeout(9_000).maxBodySize(2_000_000)
            .ignoreHttpErrors(true).followRedirects(false).execute()
        require(response.statusCode() in 200..299){
            "$engine search returned HTTP "+response.statusCode()
        }
        val html=response.body()
        val direct=parseWebIndex(html,response.url().toString(),engine)
        // Search engines generally return HTML model detail pages, not
        // URLs ending .glb/.obj. Inspect only a few independently indexed
        // pages for actual linked originals; never present the page itself
        // as an asset, subscription or paid-store redirect.
        val pages=OpenWebModelPageDiscovery.landingLinks(
            html,response.url().toString(),engine,term).take(12)
        // Previous search inspected just THREE model pages serially,
        // reducing a common query such as "sword" to almost no originals.
        // Check more authentic result pages in bounded parallel lanes,
        // rather than increasing network time or showing paid storefronts.
        val pool=Executors.newFixedThreadPool(6)
        val recovered=try{
            val jobs=pages.map{page->pool.submit<List<Candidate>>{
                runCatching{
                    val detail=Jsoup.connect(page)
                        .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                        .timeout(4_500).maxBodySize(1_800_000)
                        .ignoreHttpErrors(true).followRedirects(false).execute()
                    if(detail.statusCode() !in 200..299 ||
                        !detail.contentType().orEmpty().contains("html",true))
                        emptyList() else OpenWebModelPageDiscovery.originals(
                            detail.body(),page,term)
                }.getOrDefault(emptyList())
            }}
            jobs.flatMap{runCatching{
                it.get(9,TimeUnit.SECONDS)
            }.getOrDefault(emptyList())}
        }finally{pool.shutdownNow()}
        return (direct+recovered).distinctBy{
            SearchRanking.canonicalKey(it.url)
        }
    }

    /** Errors stay visible, never substituted with paid pages or fake assets. */
    fun find(term:String):Pair<List<Candidate>,List<String>>{
        require(term.trim().length>=2){"Enter a model name"}
        val names=listOf("Bing")
        val errors=ArrayList<String>()
        val gathered=ArrayList<Candidate>()
        val pool=Executors.newFixedThreadPool(4)
        try{
            val completion=java.util.concurrent.ExecutorCompletionService<
                Triple<String,List<Candidate>,String?>>(pool)
            var submitted=0
            val independent=listOf(
                "Federated 3D (27 indexed providers)" to {federated(term)},
                "Grab3D CC0 originals" to {grab3d(term)})
            for((name,task) in independent){
                submitted++
                completion.submit(java.util.concurrent.Callable{
                    runCatching{task()}.fold(
                        {Triple(name,it,null)},
                        {Triple(name,emptyList<Candidate>(),
                            (it.message?:it.javaClass.simpleName).take(120))})
                })
            }
            for(engine in names)for(mode in 0..1){
                submitted++
                completion.submit(java.util.concurrent.Callable{
                    val label=engine+"-"+mode
                    runCatching{searchOne(term,engine,mode)}
                        .fold({Triple(label,it,null)},{
                            Triple(label,emptyList<Candidate>(),
                                (it.message?:it.javaClass.simpleName).take(120))
                        })
                })
            }
            // The old code waited 22 seconds for EACH of four futures in
            // submission order (up to 88 seconds) and could miss a faster
            // provider simply because the first request was blocked.
            // Collect whichever independent engine completes first inside
            // ONE global search deadline.
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(22)
            var finished=0
            while(finished<submitted){
                val remaining=deadline-System.nanoTime()
                if(remaining<=0L)break
                val result=completion.poll(remaining,TimeUnit.NANOSECONDS)?:break
                finished++
                val (label,candidates,error)=result.get()
                gathered.addAll(candidates)
                if(error!=null)errors.add(label+": "+error)
            }
            if(finished<submitted)errors.add(
                (submitted-finished).toString()+" model-index requests timed out")
        }finally{pool.shutdownNow()}
        val result=gathered.asSequence().filter{
            SearchExactness.matches(term,it.title+" "+
                URI(it.url).path.substringAfterLast('/').replace('_',' ').replace('-',' '))
        }.distinctBy{SearchRanking.canonicalKey(it.url)}.take(120).toList()
        return result to errors
    }
}
