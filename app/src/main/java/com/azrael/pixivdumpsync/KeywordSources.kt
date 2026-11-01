package com.azrael.pixivdumpsync

import android.content.Context
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URLEncoder

/**
 * Multi-source video discovery. Providers may index ordinary websites, adult sites,
 * mirrors, and Tor-reachable sources. No preconfigured site-category blacklist.
 * Discovery is metadata-based; it does not bypass protected streams or DRM.
 */
enum class VideoSearchArea { HENTAI, PORN, PIRACY_DARKWEB }

object KeywordSources {
    private const val PREFS="asterion_keyword_sources"
    private fun preferences(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
    fun endpoint(context:Context):String=preferences(context).getString("searxng","").orEmpty()
    fun setEndpoint(context:Context,address:String){
        preferences(context).edit().putString("searxng",address.trim()).apply()
    }
    /** Search video websites directly; never substitute a search-engine results page. */
    /** Separate actual search sources for each of the three requested categories. */
    private val hentaiSites=listOf(
        "html|https://hanime.tv/search?query={query}|Hanime",
        "html|https://hentai.tv/series?search={query}|HentaiTV",
        "html|https://hentaihaven.xxx/?s={query}|HentaiHaven"
    )
    private val pornSites=listOf(
        "html|https://www.pornhub.com/video/search?search={query}|Pornhub",
        "html|https://www.xvideos.com/?k={query}|XVideos",
        "html|https://xhamster.com/search/{query}|xHamster",
        "html|https://www.xnxx.com/search/{query}|XNXX",
        "html|https://www.eporner.com/search/{query}/|Eporner",
        "html|https://spankbang.com/s/{query}/|SpankBang",
        "html|https://www.redtube.com/?search={query}|RedTube"
    )
    /** Indexed listings are not playable video files and open as source pages. */
    private val clearnetIndexes=listOf(
        "html|https://nyaa.si/?f=0&c=0_0&q={query}|Nyaa",
        "html|https://sukebei.nyaa.si/?page=rss&q={query}|Sukebei",
        "html|https://thepiratebay.org/search.php?q={query}|The Pirate Bay",
        "html|https://1337x.to/search/{query}/1/|1337x"
    )
    internal fun siteDefinitions(area:VideoSearchArea)=when(area){
        VideoSearchArea.HENTAI -> hentaiSites
        VideoSearchArea.PORN -> pornSites
        VideoSearchArea.PIRACY_DARKWEB -> clearnetIndexes
    }
    private fun isOldBrowser(line:String)=
        line.contains("www.bing.com/videos/search",true) ||
        line.contains("search.brave.com/search",true)
    fun sources(context:Context):String {
        // Search does not depend on choosing or configuring a search provider.
        return (hentaiSites+pornSites+clearnetIndexes).joinToString("\n")
    }
    private fun searchProviders(context:Context,area:VideoSearchArea):List<SearchProvider> {
        val direct=SearchRanking.parseProviders(siteDefinitions(area).joinToString("\n"))
        if(area!=VideoSearchArea.PIRACY_DARKWEB)return direct
        // Saved user-supplied search templates are included without a new
        // provider settings screen. Hidden sites need an existing local proxy.
        val torEnabled=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
            .getBoolean("tor_proxy",false)
        val saved=runCatching { com.asterion.videoflow.VideoDb(context).use { db->
            db.listWatches().filter{it.enabled}.mapNotNull { watch->
                val url=watch.address
                val host=runCatching{URI(url.replace("{query}","test"))
                    .host.orEmpty().lowercase()}.getOrDefault("")
                if((!url.contains("{query}") && !url.contains("{name}")) ||
                    (host.endsWith(".onion") && !torEnabled))null
                else runCatching{
                    SearchRanking.parseProviders("html|"+url+"|"+watch.label).first()
                }.getOrNull()
            }
        }}.getOrDefault(emptyList())
        return (direct+saved).distinctBy{it.url}
    }
    fun setSources(context:Context,lines:String){
        SearchRanking.parseProviders(lines) // Reject invalid values before persistence.
        preferences(context).edit().putString("providers",lines.trim()).apply()
    }
    private fun proxy(context:Context,url:String):Proxy? {
        if(!URI(url).host.orEmpty().endsWith(".onion",true))return null
        val settings=context.getSharedPreferences("video_options",Context.MODE_PRIVATE)
        if(!settings.getBoolean("tor_proxy",false))return null
        return Proxy(Proxy.Type.SOCKS,InetSocketAddress.createUnresolved(
            "127.0.0.1",settings.getInt("tor_port",9050).coerceIn(1,65535)))
    }
    private fun connection(context:Context,url:String):org.jsoup.Connection {
        require(SearchRanking.validUrl(url)){"Invalid search source address"}
        val host=URI(url).host.orEmpty().lowercase()
        val route=proxy(context,url)
        require(!host.endsWith(".onion")||route!=null){
            "Enable a running local SOCKS5/Tor proxy for .onion search sources"
        }
        val call=Jsoup.connect(url).timeout(25000).maxBodySize(4_000_000)
            .followRedirects(true).userAgent("Mozilla/5.0 (Linux; Android 16; Mobile)")
        // Do not silently fall back to an unproxied request when the proxy is enabled.
        if(route!=null)call.proxy(route)
        return call
    }
    private fun index(context:Context,provider:SearchProvider,keyword:String):List<RankedHit> {
        val escaped=URLEncoder.encode(keyword,"UTF-8")
        val address=provider.url.trimEnd('/')+
            "/search?q=$escaped&format=json&categories=videos,general&safesearch=0"
        val response=connection(context,address).ignoreContentType(true).execute()
        require(response.statusCode() in 200..299){
            "Search provider returned HTTP ${response.statusCode()}"
        }
        val found=JSONObject(response.body()).optJSONArray("results")
            ?:error("Search index did not return JSON results")
        val results=ArrayList<RankedHit>()
        for(i in 0 until minOf(found.length(),250)){
            val row=found.optJSONObject(i)?:continue
            val url=row.optString("url")
            if(!SearchRanking.validUrl(url) || !SearchRanking.isVideoUrl(url))continue
            val title=row.optString("title",url).take(220)
            val snippet=row.optString("content").take(500)
            results.add(RankedHit(title,url,provider.label,snippet))
        }
        return results
    }
    /** Hanime's search catalog is JSON, not a regular HTML list. */
    internal fun parseHanimeSearch(payload:String):List<RankedHit>{
        val response=JSONObject(payload)
        val value=response.opt("hits")
        val hits=when(value){
            is org.json.JSONArray -> value
            is String -> org.json.JSONArray(value)
            else -> org.json.JSONArray()
        }
        val found=ArrayList<RankedHit>()
        for(i in 0 until minOf(hits.length(),200)){
            val row=hits.optJSONObject(i)?:continue
            val slug=row.optString("slug").trim()
            val name=row.optString("name").ifBlank{row.optString("title")}.trim()
            if(!slug.matches(Regex("[a-zA-Z0-9_-]{3,150}"))||name.isBlank())continue
            found.add(RankedHit(name,"https://hanime.tv/hentai-videos/$slug",
                "Hanime","",thumbnail=row.optString("cover_url").takeIf{it.startsWith("https://")}))
        }
        return found
    }
    private fun hanimeApi(context:Context,keyword:String):List<RankedHit>{
        val request=JSONObject().apply{
            put("search_text",keyword)
            put("blacklist",org.json.JSONArray())
            put("brands",org.json.JSONArray())
            put("order_by","created_at_unix")
            put("ordering","desc")
            put("page",0)
            put("tags",org.json.JSONArray())
            put("tags_mode","AND")
        }
        val response=connection(context,"https://search.htv-services.com/")
            .ignoreContentType(true)
            .header("Content-Type","application/json;charset=UTF-8")
            .requestBody(request.toString())
            .method(org.jsoup.Connection.Method.POST)
            .execute()
        require(response.statusCode() in 200..299) {
            "Hanime search endpoint returned HTTP "+response.statusCode()
        }
        return parseHanimeSearch(response.body())
    }
    private fun sukebeiRss(context:Context,url:String):List<RankedHit>{
        val doc=connection(context,url).ignoreContentType(true).execute()
        require(doc.statusCode() in 200..299){"Sukebei RSS HTTP "+doc.statusCode()}
        val xml=Jsoup.parse(doc.body(),url,org.jsoup.parser.Parser.xmlParser())
        return xml.select("item").take(200).mapNotNull { item->
            val title=item.selectFirst("title")?.text()?.trim().orEmpty()
            val guid=item.selectFirst("guid")?.text()?.trim().orEmpty()
            val link=item.selectFirst("link")?.text()?.trim().orEmpty()
            val detail=when{
                SearchRanking.isIndexPage(guid)->guid
                SearchRanking.isIndexPage(link)->link
                else->null
            }
            if(title.isBlank()||detail==null)null else RankedHit(
                title,detail,"Sukebei",item.selectFirst("description")?.text().orEmpty())
        }
    }
    private fun html(context:Context,provider:SearchProvider,keyword:String,
                     area:VideoSearchArea):List<RankedHit> {
        val encoded=URLEncoder.encode(keyword,"UTF-8")
        val url=provider.url.replace("{query}",encoded).replace("{name}",encoded)
        if(area==VideoSearchArea.HENTAI && provider.label=="Hanime"){
            val direct=runCatching{hanimeApi(context,keyword)}
            if(direct.isSuccess && direct.getOrDefault(emptyList()).isNotEmpty())
                return direct.getOrThrow()
            // Older integrations use an API host that may no longer resolve.
            // Try the actual website HTML before declaring this source unavailable.
            val fallback="https://hanime.tv/search?query="+encoded
            val page=runCatching{connection(context,fallback).get()}
            if(page.isFailure)
                throw IllegalStateException("Hanime API and HTML search unavailable: "+
                    (page.exceptionOrNull()?.message?:direct.exceptionOrNull()?.message))
            return htmlPageHits(page.getOrThrow(),fallback,provider,keyword,area)
        }
        if(area==VideoSearchArea.PIRACY_DARKWEB && provider.label=="Sukebei")
            return sukebeiRss(context,url)
        val doc=connection(context,url).get()
        return htmlPageHits(doc,url,provider,keyword,area)
    }
    internal fun htmlPageHits(doc:Document,url:String,provider:SearchProvider,
        keyword:String,area:VideoSearchArea):List<RankedHit>{
        val found=ArrayList<RankedHit>()
        val selectors=".cat-card a[href], li.pcVideoListItem a[href], .videoBox a[href], "+
            ".thumb-block a[href], [class*=video-thumb] a[href], "+
            "[class*=video-card] a[href], [class*=thumb] a[href], "+
            "[class*=video] a[href], article a[href], "+
            "table.torrent-list a[href], .coll-1 a[href], tr a[href], a[href]"
        for(node in doc.select(selectors).take(5000)){
            if(node.closest("header,footer,nav,aside,.menu,.sidebar,.navigation")!=null)continue
            val absolute=runCatching{
                URI(doc.location()).resolve(node.attr("href")).toString()
            }.getOrNull()?:continue
            if(!SearchRanking.validUrl(absolute) ||
                !(SearchRanking.isVideoUrl(absolute) ||
                    (area==VideoSearchArea.PIRACY_DARKWEB &&
                        SearchRanking.isIndexPage(absolute))))continue
            val resultHost=runCatching{URI(absolute).host.orEmpty().removePrefix("www.")}.getOrDefault("")
            val searchHost=URI(url).host.orEmpty().removePrefix("www.")
            if(resultHost!=searchHost && !resultHost.endsWith("."+searchHost))continue
            val container=node.closest(
                ".cat-card, li.pcVideoListItem, .videoBox, .thumb-block, "+
                "[class*=video-thumb], [class*=video-card], article, "+
                "[class*=thumb], [class*=video]")
            val image=(node.selectFirst("img")?:container?.selectFirst("img"))
            val description=container?.text().orEmpty().take(500)
            val titles=listOf(
                container?.selectFirst(".cat-title,h3,h2,[class*=title]")?.text().orEmpty(),
                node.attr("title"),node.attr("aria-label"),
                image?.attr("alt").orEmpty(),node.text(),
                container?.attr("title").orEmpty()
            ).map{it.trim().take(220)}.filter{it.length>=5}
            val title=titles.maxByOrNull{
                SearchRanking.relevance(keyword,it,description)
            }?:continue
            if(SearchRanking.relevance(keyword,title,description)==0)continue
            val thumb=image?.let{img->
                listOf("data-thumb","data-thumb_url","data-preview",
                    "data-original","data-src","data-lazy-src","src")
                    .map{img.attr(it)}.firstOrNull{it.startsWith("http")||
                        it.startsWith("//")||it.startsWith("/")}
                    ?.let{runCatching{URI(doc.location()).resolve(it).toString()}.getOrNull()}
            }?.takeIf{SearchRanking.validUrl(it)}
            found.add(RankedHit(title,absolute,provider.label,description,
                thumbnail=thumb))
            if(found.size>=500)break
        }
        return found
    }
    fun searchDetailed(context:Context,keyword:String,
                       area:VideoSearchArea=VideoSearchArea.PORN):SearchOutcome {
        require(keyword.trim().isNotBlank()){"Enter a search term"}
        val providers=searchProviders(context,area)
        if(providers.isEmpty())return SearchOutcome(emptyList(),0,
            listOf("No search sources for this area. Hidden services require a configured local Tor proxy."))
        val pool=java.util.concurrent.Executors.newFixedThreadPool(minOf(6,providers.size))
        val completion=java.util.concurrent.ExecutorCompletionService<
            Triple<String,List<RankedHit>,String?>>(pool)
        val collected=ArrayList<RankedHit>()
        val errors=ArrayList<String>()
        try {
            for(provider in providers)completion.submit(java.util.concurrent.Callable {
                try {
                    val hits=when(provider.kind){
                        SearchProviderKind.SEARXNG->index(context,provider,keyword.trim())
                        SearchProviderKind.HTML->html(context,provider,keyword.trim(),area)
                    }
                    Triple(provider.label,hits,null)
                }catch(t:Exception){
                    Triple(provider.label,emptyList(),
                        t.message?:t.javaClass.simpleName)
                }
            })
            // Execute independent providers concurrently. A broken site cannot hold
            // the whole search queue hostage.
            val deadline=System.nanoTime()+
                java.util.concurrent.TimeUnit.SECONDS.toNanos(90)
            var finished=0
            while(finished<providers.size){
                val remaining=deadline-System.nanoTime()
                if(remaining<=0L)break
                val result=completion.poll(remaining,
                    java.util.concurrent.TimeUnit.NANOSECONDS)?:break
                val (label,hits,error)=result.get()
                finished++
                collected.addAll(hits)
                if(error!=null)errors.add("$label: $error")
            }
            if(finished<providers.size)
                errors.add("${providers.size-finished} search source(s) timed out")
        }finally{pool.shutdownNow()}
        return SearchOutcome(
            if(area==VideoSearchArea.PIRACY_DARKWEB)
                SearchRanking.deduplicateIndex(keyword,collected)
            else SearchRanking.deduplicate(keyword,collected),
            providers.size,errors)
    }
    /** Search one explicitly supplied site, including HTML sites with unusual page URLs. */
    fun searchCustom(context:Context,keyword:String,address:String):SearchOutcome {
        require(SearchRanking.validUrl(
            address.replace("{query}","sample").replace("{name}","sample"))) {
            "Invalid user-supplied source URL"
        }
        val provider=SearchProvider(SearchProviderKind.HTML,address,"Custom site")
        return try {
            SearchOutcome(SearchRanking.deduplicate(keyword,
                html(context,provider,keyword,VideoSearchArea.PIRACY_DARKWEB)),1,emptyList())
        }catch(t:Exception) {
            SearchOutcome(emptyList(),1,listOf(
                "Custom site: ${t.message?:t.javaClass.simpleName}"))
        }
    }
    /** Compatibility entry point for older UI and stored creator watch records. */
    fun search(context:Context,keyword:String):List<Pair<String,String>> =
        searchDetailed(context,keyword).hits.map{it.title to it.url}
}
