package com.azrael.pixivdumpsync

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Search for actual public EPUB files, downloadable game archives and torrent metadata.
 * A listing is not a completed download and does not imply engine compatibility.
 */
enum class CatalogKind { EPUB, APK, GAME_ARCHIVE, TORRENT }
data class CatalogHit(val title:String, val source:String, val url:String,
                      val kind:CatalogKind, val filename:String="", val details:String="")
data class CatalogOutcome(val hits:List<CatalogHit>, val failures:List<String>, val searched:Int)

object CatalogDiscovery {
    private fun enc(s:String)=URLEncoder.encode(s.trim(),"UTF-8")
    private fun get(url:String):String {
        require(SearchRanking.validUrl(url) && URI(url).scheme=="https")
        val response=Jsoup.connect(url).ignoreContentType(true)
            .userAgent("Mozilla/5.0 (Linux; Android 17; Asterion Downloader)")
            .timeout(16000).maxBodySize(3_000_000).followRedirects(true).execute()
        require(response.statusCode() in 200..299){"HTTP "+response.statusCode()}
        return response.body()
    }
    internal fun gutendex(json:String):List<CatalogHit> {
        val array=JSONObject(json).optJSONArray("results") ?: return emptyList()
        val hits=ArrayList<CatalogHit>()
        for(i in 0 until minOf(array.length(),32)) {
            val book=array.optJSONObject(i)?:continue
            val title=book.optString("title").trim()
            val epub=book.optJSONObject("formats")?.optString("application/epub+zip").orEmpty()
            if(title.isBlank() || !epub.startsWith("https://") || !SearchRanking.validUrl(epub))continue
            val author=book.optJSONArray("authors")?.optJSONObject(0)?.optString("name").orEmpty()
            hits+=CatalogHit(title,"Project Gutenberg",epub,CatalogKind.EPUB,
                safeFilename(title,"epub"),author)
        }
        return hits
    }
    private fun searchGutenberg(q:String):List<CatalogHit> =
        gutendex(get("https://gutendex.com/books/?mime_type=application%2Fepub&search="+enc(q)))

    internal fun archiveSearch(json:String):List<Pair<String,String>> {
        val arr=JSONObject(json).optJSONObject("response")?.optJSONArray("docs")
            ?:return emptyList()
        val results=ArrayList<Pair<String,String>>()
        for(i in 0 until minOf(arr.length(),12)) {
            val row=arr.optJSONObject(i)?:continue
            val id=row.optString("identifier")
            val title=row.optString("title").ifBlank{id}.take(180)
            if(id.matches(Regex("[a-zA-Z0-9_.-]{3,150}")) && title.isNotBlank())
                results+=id to title
        }
        return results
    }
    internal fun archiveFiles(json:String,id:String,title:String,games:Boolean):List<CatalogHit> {
        val obj=JSONObject(json)
        val metadata=obj.optJSONObject("metadata")?:return emptyList()
        if(obj.optBoolean("is_restricted") ||
            metadata.optString("access-restricted-item").equals("true",true)) return emptyList()
        val files=obj.optJSONArray("files")?:return emptyList()
        val hits=ArrayList<CatalogHit>()
        for(i in 0 until minOf(files.length(),1000)) {
            val f=files.optJSONObject(i)?:continue
            if(f.optString("private").equals("true",true))continue
            val name=f.optString("name").trim()
            val lower=name.lowercase(Locale.ROOT)
            if(name.startsWith("/")||name.contains('\\')||name.contains("../")||
                lower.endsWith("_lcp.epub")||lower.endsWith(".acsm"))continue
            val kind=when{
                !games && lower.endsWith(".epub")->CatalogKind.EPUB
                games && lower.endsWith(".apk")->CatalogKind.APK
                games && lower.endsWith(".zip")->CatalogKind.GAME_ARCHIVE
                else->continue
            }
            val parts=name.split('/').map{enc(it).replace("+","%20")}
            val url="https://archive.org/download/"+id+"/"+parts.joinToString("/")
            val file=name.substringAfterLast('/')
            val extension=if(kind==CatalogKind.EPUB)"epub" else if(kind==CatalogKind.APK)"apk" else "zip"
            hits+=CatalogHit(title,"Internet Archive",url,kind,
                safeFilename(file,extension),file+" · "+(f.optLong("size")/1024/1024)+" MiB")
            if(hits.size>=6)break
        }
        return hits
    }
    private fun searchArchive(query:String,games:Boolean):List<CatalogHit> {
        val title=query.replace(Regex("[^\\p{L}\\p{N}\\s-]")," ")
            .replace(Regex("\\s+")," ").trim()
        if(title.length<2)return emptyList()
        val q="title:("+title+") AND mediatype:"+(if(games)"software" else "texts")
        val url="https://archive.org/advancedsearch.php?q="+enc(q)+
            "&output=json&rows=12&fl%5B%5D=identifier&fl%5B%5D=title"
        val entries=archiveSearch(get(url))
        val hits=ArrayList<CatalogHit>()
        for((id,name) in entries.take(9)) {
            val metadata=runCatching{get("https://archive.org/metadata/"+id)}.getOrNull()?:continue
            hits+=archiveFiles(metadata,id,name,games)
        }
        return hits
    }
    internal fun torrentRss(xml:String,base:String,source:String):List<CatalogHit> {
        val doc=Jsoup.parse(xml,base,Parser.xmlParser())
        return doc.select("item").take(100).mapNotNull{item->
            val title=item.selectFirst("title")?.text()?.trim().orEmpty()
            val link=item.selectFirst("link")?.text()?.trim().orEmpty().ifBlank{
                item.selectFirst("guid")?.text()?.trim().orEmpty()
            }
            val enclosure=item.selectFirst("enclosure")?.attr("url").orEmpty()
            val selected=when{
                enclosure.startsWith("https://") &&
                    enclosure.substringBefore('?').endsWith(".torrent",true)->enclosure
                SearchRanking.isIndexPage(link)->link
                else->return@mapNotNull null
            }
            if(title.isBlank()||!SearchRanking.validUrl(selected))null
            else CatalogHit(title,source,selected,CatalogKind.TORRENT,"",
                "Torrent metadata; select actual game files before transfer")
        }
    }
    private fun searchTorrents(q:String):List<CatalogHit> {
        val providers=listOf("Nyaa" to "https://nyaa.si/?page=rss&q=",
            "Sukebei" to "https://sukebei.nyaa.si/?page=rss&q=")
        val hits=ArrayList<CatalogHit>()
        val errors=ArrayList<String>()
        for((name,base) in providers){
            runCatching{torrentRss(get(base+enc(q)),base,name)}
                .onSuccess{hits+=it}.onFailure{errors+=name+": "+(it.message?:"Unavailable")}
        }
        if(hits.isEmpty() && errors.isNotEmpty())error(errors.joinToString(" · "))
        return hits
    }
    internal fun safeFilename(input:String,extension:String):String {
        val base=input.replace(Regex("[^\\p{L}\\p{N}_. -]"),"_")
            .trim().take(95).ifBlank{"download"}
        return if(base.endsWith("."+extension,true))base else base+"."+extension
    }
    internal fun relevance(query:String,title:String):Int {
        val words=query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter{it.length>1}
        if(words.isEmpty())return 0
        val t=title.lowercase(Locale.ROOT)
        val count=words.count{t.contains(it)}
        return if(count==0)0 else count*20+(if(t.contains(query.lowercase(Locale.ROOT)))100 else 0)
    }
    fun search(query:String,games:Boolean):CatalogOutcome {
        val title=query.trim()
        require(title.length in 2..150){"Enter a title (2–150 characters)"}
        val providers:List<Pair<String,()->List<CatalogHit>>>=
            if(games)listOf("Internet Archive" to {searchArchive(title,true)})
            else listOf("Project Gutenberg" to {searchGutenberg(title)},
                "Internet Archive" to {searchArchive(title,false)})
        val pool=Executors.newFixedThreadPool(providers.size)
        val work=ExecutorCompletionService<Pair<List<CatalogHit>,String?>>(pool)
        val all=ArrayList<CatalogHit>()
        val failures=ArrayList<String>()
        try {
            for((name,fn) in providers) work.submit(Callable{
                try{fn() to null}
                catch(e:Exception){emptyList<CatalogHit>() to (name+": "+(e.message?:e.javaClass.simpleName))}
            })
            repeat(providers.size){
                val future=work.poll(50,TimeUnit.SECONDS)
                if(future==null)failures+="Catalog source timed out"
                else{
                    val (hits,error)=future.get()
                    all+=hits
                    if(error!=null)failures+=error
                }
            }
        }finally{pool.shutdownNow()}
        val results=all.filter{games || it.kind==CatalogKind.EPUB}
            .filter{relevance(title,it.title)>0}
            .distinctBy{SearchRanking.canonicalKey(it.url)}
            .sortedWith(compareByDescending<CatalogHit>{relevance(title,it.title)}
                .thenBy{it.source}).take(60)
        return CatalogOutcome(results,failures,providers.size)
    }
}
