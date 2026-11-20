package com.azrael.pixivdumpsync

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Public torrent metadata providers. Only HTTPS API requests; no unknown mirror redirects,
 * executable installers, site ads, or third-party "download client" binaries are accepted.
 */
enum class TorrentArea { GAMES, BOOKS, ADULT }

object MultiTorrentSearch {
    private fun enc(s:String)=URLEncoder.encode(s.trim(),"UTF-8")
    private val hashPattern=Regex("(?i)^[a-f0-9]{40}$")
    /** Show an index-owned poster only if its API published an actual image
     * address. Do not scrape random search images for a torrent identity. */
    private fun poster(row:JSONObject):String{
        for(key in listOf("coverUrl","poster","thumbnail","image","cover",
                "posterUrl","imageUrl")){
            val candidate=row.optString(key).trim()
            if(candidate.startsWith("https://") &&
                SearchRanking.validUrl(candidate) &&
                runCatching{java.net.URI(candidate).path.orEmpty()
                    .matches(Regex(""".*\.(?:jpe?g|png|webp|avif)$""",
                        RegexOption.IGNORE_CASE))}.getOrDefault(false))
                return candidate
        }
        return ""
    }
    // Index APIs often return only an infohash, not the original .torrent
    // descriptor or any announce trackers. A trackerless synthesized magnet
    // can wait indefinitely for DHT bootstrap on Android mobile networks.
    // Preserve the exact publisher infohash; add only documented public
    // announce endpoints, retaining libtorrent's existing DHT fallback.
    // These are NOT download/verification services or substitute media.
    private val publicTrackers=listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.demonii.com:1337/announce"
    )
    private fun magnet(hash:String,title:String):String =
        "magnet:?xt=urn:btih:"+hash.uppercase()+"&dn="+enc(title.take(150))+
            publicTrackers.joinToString(""){"&tr="+enc(it)}
    private fun request(url:String,body:String?=null):String {
        val host=URL(url).host.lowercase()
        require(host in setOf("bitsearch.eu","api.knaben.org","apibay.org")){"Untrusted torrent index host"}
        val connection=URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout=14000;connection.readTimeout=18000
        connection.instanceFollowRedirects=false
        connection.setRequestProperty("Accept","application/json")
        connection.setRequestProperty("User-Agent","AsterionDownloader/1.3")
        if(body!=null){
            connection.requestMethod="POST";connection.doOutput=true
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8")
            connection.outputStream.use{it.write(body.toByteArray(Charsets.UTF_8))}
        }
        try{
            require(connection.responseCode in 200..299){"HTTP "+connection.responseCode}
            return connection.inputStream.bufferedReader().use{it.readText().take(2_000_000)}
        }finally{connection.disconnect()}
    }
    internal fun bitsearch(json:String):List<CatalogHit>{
        val arr=JSONObject(json).optJSONArray("results")?:return emptyList()
        val result=ArrayList<CatalogHit>()
        for(i in 0 until minOf(arr.length(),75)){
            val row=arr.optJSONObject(i)?:continue
            val title=row.optString("title").trim()
            val hash=row.optString("infohash")
            if(title.isEmpty() || !hashPattern.matches(hash))continue
            val category=row.optString("category")
            val seeders=row.optInt("seeders")
            result+=CatalogHit(title,"Bitsearch",magnet(hash,title),CatalogKind.TORRENT,"",
                "Peers: $seeders · $category · file selection required",
                poster(row))
        }
        return result
    }
    internal fun knaben(json:String):List<CatalogHit>{
        val arr=JSONObject(json).optJSONArray("hits")?:return emptyList()
        val result=ArrayList<CatalogHit>()
        for(i in 0 until minOf(arr.length(),100)){
            val row=arr.optJSONObject(i)?:continue
            if(row.optDouble("virusDetection",0.0)>0.3)continue
            val title=row.optString("title").trim()
            val raw=row.optString("magnetUrl")
            val hash=row.optString("hash")
            if(title.isEmpty())continue
            val url=when{
                raw.startsWith("magnet:?xt=urn:btih:",true) && raw.length<5000->raw
                hashPattern.matches(hash)->magnet(hash,title)
                else->continue
            }
            result+=CatalogHit(title,"Knaben ("+row.optString("tracker").take(24)+")",
                url,CatalogKind.TORRENT,"",
                "Peers: "+row.optInt("seeders")+" · "+row.optString("category").take(50),
                poster(row))
        }
        return result
    }
    internal fun apibay(json:String,range:IntRange=400..499):List<CatalogHit>{
        val arr=JSONArray(json)
        return (0 until minOf(arr.length(),95)).mapNotNull{i->
            val row=arr.optJSONObject(i)?:return@mapNotNull null
            val title=row.optString("name")
            val hash=row.optString("info_hash")
            val category=row.optInt("category")
            if(!hashPattern.matches(hash) || title.isBlank() || category !in range)
                return@mapNotNull null
            CatalogHit(title,"The Pirate Bay index",magnet(hash,title),CatalogKind.TORRENT,"",
                "Seeders: "+row.optInt("seeders")+" · metadata only",
                poster(row))
        }
    }

    /** GGBases is a Chinese game-specific torrent/magnet catalog, unlike
     * unrestricted RSS feeds which mixed adult video releases into Games. */
    internal fun ggbasesListing(html:String,query:String):List<CatalogHit>{
        val base="https://www.ggbases.com/"
        val doc=Jsoup.parse(html,base)
        val visited=HashSet<String>()
        val items=ArrayList<CatalogHit>()
        for(a in doc.select("a[href*='view.so?id=']").take(2000)){
            val url=a.absUrl("href")
            val uri=runCatching{java.net.URI(url)}.getOrNull()?:continue
            if(uri.scheme!="https" || uri.host!="www.ggbases.com" ||
                uri.path!="/view.so" ||
                !Regex("(?:^|&)id=[0-9]{1,12}(?:&|$)").containsMatchIn(
                    uri.rawQuery.orEmpty()))continue
            val name=a.attr("title").takeIf{it.length>=4}
                ?:a.text().trim().takeIf{it.length>=4}
                ?:continue
            if(CatalogDiscovery.relevance(query,name)<=0 ||
                !visited.add(url))continue
            items+=CatalogHit(name.take(220),"GGBases · Chinese game torrents",
                url,CatalogKind.TORRENT,"",
                "Game release listing · original torrent/magnet resolved on the detail page")
            if(items.size>=45)break
        }
        return items
    }
    private fun searchGGBases(q:String):List<CatalogHit>{
        val url="https://www.ggbases.com/search.so?p=0&title="+enc(q)
        val response=Jsoup.connect(url).timeout(16000)
            .userAgent("Mozilla/5.0 (Linux; Android 17) Chrome/131.0")
            .ignoreHttpErrors(true).followRedirects(false)
            .maxBodySize(3_000_000).execute()
        if(response.statusCode() in 300..399){
            val location=response.header("Location").orEmpty()
            require(location.startsWith("https://www.ggbases.com/")){
                "Provider redirects away from HTTPS; not followed"
            }
            val next=Jsoup.connect(location).timeout(14000)
                .userAgent("Mozilla/5.0 (Linux; Android 17) Chrome/131.0")
                .ignoreHttpErrors(true).followRedirects(false)
                .maxBodySize(3_000_000).execute()
            require(next.statusCode() in 200..299){"Provider HTTPS "+next.statusCode()}
            return ggbasesListing(next.body(),q)
        }
        require(response.statusCode() in 200..299){
            "Provider HTTPS "+response.statusCode()
        }
        return ggbasesListing(response.body(),q)
    }

    fun search(q:String):Pair<List<CatalogHit>,List<String>> = search(q,TorrentArea.GAMES)

    fun search(q:String,area:TorrentArea):Pair<List<CatalogHit>,List<String>>{
        val hits=ArrayList<CatalogHit>()
        val errors=ArrayList<String>()
        val providers=ArrayList<Pair<String,()->List<CatalogHit>>>()
        val bit=when(area){
            TorrentArea.GAMES->6;TorrentArea.BOOKS->9;TorrentArea.ADULT->10
        }
        val bay=when(area){
            TorrentArea.GAMES->400;TorrentArea.BOOKS->600;TorrentArea.ADULT->500
        }
        // Register independently bounded existing providers. Earlier
        // sequential 14–20s network timeouts multiplied across six sites,
        // leaving the entire search looking stuck for more than a minute.
        fun provider(name:String,run:()->List<CatalogHit>){
            providers+=name to run
        }
        provider("Bitsearch"){
            bitsearch(request("https://bitsearch.eu/api/v1/search?q="+enc(q)+
                "&category="+bit+"&limit=100"))
        }
        provider("Pirate Bay"){
            apibay(request("https://apibay.org/q.php?q="+enc(q)+"&cat="+bay),
                bay..(bay+99))
        }
        provider("Knaben"){
            val body=JSONObject().apply{
                put("search_type","score");put("search_field","title");put("query",q)
                put("size",80);put("hide_unsafe",true)
            }.toString()
            knaben(request("https://api.knaben.org/v1",body)).filter{
                val name=(it.details+" "+it.title).lowercase()
                when(area){
                    // Adult includes full-scene video AND photo sets /
                    // cosplay galleries. A title match alone is never enough
                    // to misclassify a different release category.
                    TorrentArea.ADULT->Regex(
                        """(?i)\b(porn|xxx|adult|erotic|video|videos|images|gallery|galleries|photos|pictures)\b"""
                    ).containsMatchIn(name)
                    TorrentArea.BOOKS->Regex(
                        """(?i)\b(books?|epubs?|novels?)\b"""
                    ).containsMatchIn(name)
                    TorrentArea.GAMES->Regex("(?i)\\b(games?|software|visual.novel|ren.?py|eroge|rpg|vn)\\b")
                        .containsMatchIn(name) &&
                        !Regex("(?i)\\b(porn.?video|anime.?episode|movie|jav|video.?rip)\\b")
                            .containsMatchIn(name)
                }
            }
        }
        if(area==TorrentArea.GAMES)provider("GGBases · CN games"){
            searchGGBases(q)
        }
        if(area==TorrentArea.ADULT || area==TorrentArea.GAMES){
            provider("Sukebei"){
                // Adult games rather than all adult releases (especially video).
                val cat=if(area==TorrentArea.GAMES)"&c=1_3" else ""
                val url="https://sukebei.nyaa.si/?page=rss"+cat+"&q="+enc(q)
                val data=org.jsoup.Jsoup.connect(url).ignoreContentType(true)
                    .userAgent("Mozilla/5.0").timeout(14000).execute().body()
                CatalogDiscovery.torrentRss(data,url,"Sukebei")
            }
        }
        if(area==TorrentArea.GAMES){
            provider("Nyaa · software games"){
                val url="https://nyaa.si/?page=rss&c=6_2&q="+enc(q)
                val data=org.jsoup.Jsoup.connect(url).ignoreContentType(true)
                    .userAgent("Mozilla/5.0").timeout(14000).execute().body()
                CatalogDiscovery.torrentRss(data,url,"Nyaa")
            }
        }
        val pool=java.util.concurrent.Executors.newFixedThreadPool(
            minOf(4,providers.size.coerceAtLeast(1)))
        val completion=java.util.concurrent.ExecutorCompletionService<
            Pair<String,Result<List<CatalogHit>>>>(pool)
        val pending=providers.map{it.first}.toMutableSet()
        try{
            for((name,task) in providers){
                completion.submit(java.util.concurrent.Callable{
                    name to runCatching{task()}
                })
            }
            val deadline=System.nanoTime()+
                java.util.concurrent.TimeUnit.SECONDS.toNanos(28)
            repeat(providers.size){
                val left=(deadline-System.nanoTime()).coerceAtLeast(0L)
                if(left==0L || Thread.currentThread().isInterrupted)return@repeat
                val outcome=completion.poll(left,
                    java.util.concurrent.TimeUnit.NANOSECONDS)?:return@repeat
                val (providerName,result)=outcome.get()
                pending.remove(providerName)
                result.onSuccess{hits+=it}.onFailure{
                    errors+=providerName+": "+(it.message?:"Unavailable")
                }
            }
            for(name in pending)
                errors+=name+": unavailable within search deadline"
        }finally{
            // Cancel late network reads when the owner navigates away or
            // requests a newer search. No zombie provider threads.
            pool.shutdownNow()
        }
        // APIs frequently return titles matching just ONE query character
        // or a token buried in an unrelated release. Require the actual
        // title to include EVERY significant search term before displaying it.
        // Adult torrent images/galleries are allowed; quality and identity are
        // still checked against the selected descriptor before downloading.
        return hits.filter{
            SearchExactness.matches(q,it.title) &&
                (area!=TorrentArea.GAMES ||
                    !GameTorrentPolicy.clearlyNonGameTitle(it.title))
        }.distinctBy{
            if(it.url.startsWith("magnet:"))it.url.substringAfter("urn:btih:").substringBefore("&").lowercase()
            else it.url
        } to errors
    }

}
