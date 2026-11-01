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
    private fun magnet(hash:String,title:String):String =
        "magnet:?xt=urn:btih:"+hash.uppercase()+"&dn="+enc(title.take(150))
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
                "Peers: $seeders · $category · file selection required")
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
                "Peers: "+row.optInt("seeders")+" · "+row.optString("category").take(50))
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
                "Seeders: "+row.optInt("seeders")+" · metadata only")
        }
    }
    fun search(q:String):Pair<List<CatalogHit>,List<String>>{
        val results=ArrayList<CatalogHit>()
        val errors=ArrayList<String>()
        fun source(label:String,fn:()->List<CatalogHit>){
            runCatching{fn()}.onSuccess{results+=it}
                .onFailure{errors+=label+": "+(it.message?:"Unavailable")}
        }
        source("Bitsearch"){
            bitsearch(request("https://bitsearch.eu/api/v1/search?q="+enc(q)+"&category=6&limit=70"))
        }
        source("Knaben"){
            val post=JSONObject().apply {
                put("search_type","score");put("search_field","title");put("query",q)
                put("size",80);put("hide_unsafe",true);put("hide_xxx",false)
            }.toString()
            knaben(request("https://api.knaben.org/v1",post))
        }
        source("The Pirate Bay"){
            apibay(request("https://apibay.org/q.php?q="+enc(q)+"&cat=400"))
        }
        return results to errors
    }

    fun search(q:String,area:TorrentArea):Pair<List<CatalogHit>,List<String>>{
        val hits=ArrayList<CatalogHit>()
        val errors=ArrayList<String>()
        val bit=when(area){
            TorrentArea.GAMES->6;TorrentArea.BOOKS->9;TorrentArea.ADULT->10
        }
        val bay=when(area){
            TorrentArea.GAMES->400;TorrentArea.BOOKS->600;TorrentArea.ADULT->500
        }
        fun provider(name:String,run:()->List<CatalogHit>){
            runCatching{run()}.onSuccess{hits+=it}.onFailure{
                errors+=name+": "+(it.message?:"Unavailable")
            }
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
                    TorrentArea.ADULT->name.contains("xxx")||name.contains("porn")||
                        name.contains("adult")||name.contains("erotic")
                    TorrentArea.BOOKS->name.contains("book")||name.contains("epub")||
                        name.contains("novel")
                    TorrentArea.GAMES->name.contains("game")||name.contains("vn")||
                        name.contains("pc")
                }
            }
        }
        if(area==TorrentArea.ADULT || area==TorrentArea.GAMES){
            provider("Sukebei"){
                val url="https://sukebei.nyaa.si/?page=rss&q="+enc(q)
                val data=org.jsoup.Jsoup.connect(url).ignoreContentType(true)
                    .userAgent("Mozilla/5.0").timeout(14000).execute().body()
                CatalogDiscovery.torrentRss(data,url,"Sukebei")
            }
        }
        if(area==TorrentArea.BOOKS){
            provider("Nyaa"){
                val url="https://nyaa.si/?page=rss&q="+enc(q)
                val data=org.jsoup.Jsoup.connect(url).ignoreContentType(true)
                    .userAgent("Mozilla/5.0").timeout(14000).execute().body()
                CatalogDiscovery.torrentRss(data,url,"Nyaa")
            }
        }
        return hits.distinctBy{
            if(it.url.startsWith("magnet:"))it.url.substringAfter("urn:btih:").substringBefore("&").lowercase()
            else it.url
        } to errors
    }

}
