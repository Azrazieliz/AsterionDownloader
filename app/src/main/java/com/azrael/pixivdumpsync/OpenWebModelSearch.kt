package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
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
                         val host:String,val thumbnail:String?=null)

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
        val names=listOf("DuckDuckGo","Bing")
        val errors=ArrayList<String>()
        val gathered=ArrayList<Candidate>()
        val pool=Executors.newFixedThreadPool(4)
        try{
            val futures=names.flatMap{engine->
                (0..1).map{mode->
                    (engine+"-"+mode) to pool.submit<List<Candidate>>{
                        searchOne(term,engine,mode)
                    }
                }
            }
            for((name,future) in futures){
                runCatching{future.get(22,TimeUnit.SECONDS)}
                    .onSuccess{gathered.addAll(it)}
                    .onFailure{errors.add(name+": "+
                        (it.cause?.message?:it.message?:"Unavailable").take(120))}
            }
        }finally{pool.shutdownNow()}
        val result=gathered.asSequence().filter{
            SearchExactness.matches(term,it.title+" "+
                URI(it.url).path.substringAfterLast('/').replace('_',' ').replace('-',' '))
        }.distinctBy{SearchRanking.canonicalKey(it.url)}.take(60).toList()
        return result to errors
    }
}
