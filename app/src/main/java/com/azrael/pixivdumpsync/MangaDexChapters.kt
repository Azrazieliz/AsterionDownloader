package com.azrael.pixivdumpsync

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

/**
 * English MangaDex series and chapter originals through its published v5 API.
 * No HTML thumbnail guessing, translation substitutions or preview pages.
 *
 * Chapter media is selected ONLY by MangaDex's /at-home/server/<chapter-id>
 * manifest, keeping the publisher's exact ordered, full-resolution data[].
 * No downloader queue, user data, local files or signed URL is modified here.
 */
internal object MangaDexChapters {
    private const val API="https://api.mangadex.org"
    private val uuid=Regex(
        """(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""
    )
    private val manga=Regex(
        """^/title/([0-9a-fA-F-]{36})(?:/[a-zA-Z0-9._~-]+)?/?$"""
    )
    private val chapter=Regex(
        """^/chapter/([0-9a-fA-F-]{36})/?$"""
    )
    private val name=Regex("""[A-Za-z0-9._~-]{5,210}""")
    private val hash=Regex("""[a-fA-F0-9]{16,128}""")
    @Volatile private var lastRequestMs=0L
    private val throttle=Any()

    private fun path(url:String):String?=runCatching{
        val u=URI(url)
        val host=u.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
        if(u.scheme!="https" || host!="mangadex.org" ||
            u.userInfo!=null || u.fragment!=null || u.query!=null)
            null else u.path
    }.getOrNull()

    fun isSeries(url:String):Boolean=path(url)?.let{p->
        manga.matchEntire(p)?.groupValues?.getOrNull(1)?.let{uuid.matches(it)}
    }==true

    fun isReader(url:String):Boolean=path(url)?.let{p->
        chapter.matchEntire(p)?.groupValues?.getOrNull(1)?.let{uuid.matches(it)}
    }==true

    internal fun mangaId(url:String):String {
        require(isSeries(url)){"Select a specific MangaDex title"}
        return requireNotNull(manga.matchEntire(path(url).orEmpty()))
            .groupValues[1].lowercase(Locale.ROOT)
    }
    internal fun chapterId(url:String):String {
        require(isReader(url)){"Select a specific MangaDex chapter"}
        return requireNotNull(chapter.matchEntire(path(url).orEmpty()))
            .groupValues[1].lowercase(Locale.ROOT)
    }

    /** HTTP is restricted to the MangaDex API, with bounded responses. */
    private fun json(route:String,limit:Int=3_000_000):JSONObject {
        require(route.startsWith("/") && !route.startsWith("//") &&
            !route.contains("..") && !route.contains('\n'))
        synchronized(throttle){
            val gap=System.currentTimeMillis()-lastRequestMs
            if(gap in 0..349)Thread.sleep(350-gap)
            lastRequestMs=System.currentTimeMillis()
        }
        val conn=(URL(API+route).openConnection() as HttpURLConnection).apply{
            instanceFollowRedirects=false
            connectTimeout=12_000
            readTimeout=25_000
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","AsterionDownloader/1.3 (Android; MangaDex English reader)")
        }
        try {
            val code=conn.responseCode
            if(code !in 200..299)
                throw java.io.IOException("MangaDex API HTTP $code")
            val contentType=conn.contentType.orEmpty()
            require(contentType.startsWith("application/json",true)){
                "MangaDex API returned HTML, not a chapter manifest"
            }
            val bytes=conn.inputStream.use{stream->
                val output=java.io.ByteArrayOutputStream()
                val buffer=ByteArray(16*1024)
                while(true){
                    val count=stream.read(buffer)
                    if(count<0)break
                    require(output.size()+count<=limit){
                        "MangaDex API response exceeds bounded JSON size"
                    }
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
            val body=JSONObject(String(bytes,Charsets.UTF_8))
            require(body.optString("result")=="ok"){
                "MangaDex API did not verify the request"
            }
            return body
        }finally{conn.disconnect()}
    }

    internal fun parseFeed(response:JSONObject):List<ChapterLink> {
        require(response.optString("result")=="ok"){
            "MangaDex API did not validate the English chapter listing"
        }
        val entries=response.optJSONArray("data")
            ?:error("MangaDex did not provide its chapter list")
        val found=LinkedHashMap<String,ChapterLink>()
        for(i in 0 until entries.length()){
            val row=entries.optJSONObject(i)?:continue
            val id=row.optString("id")
            if(!uuid.matches(id))continue
            val attr=row.optJSONObject("attributes")?:continue
            if(attr.optString("translatedLanguage")!="en")continue
            if(attr.optString("externalUrl").isNotBlank())continue
            val number=attr.optString("chapter").take(25)
                .takeIf{it.matches(Regex("""\d{1,5}(?:\.\d{1,4})?"""))}
            val volume=attr.optString("volume").take(16)
                .takeIf{it.matches(Regex("""\d{1,4}(?:\.\d{1,4})?"""))}
            val label=buildString{
                if(volume!=null)append("Volume $volume · ")
                if(number!=null)append("Chapter $number")
                else append("Chapter ").append(i+1)
                val title=attr.optString("title").trim().take(110)
                if(title.isNotEmpty())append(" · ").append(title)
            }
            val address="https://mangadex.org/chapter/"+id.lowercase(Locale.ROOT)
            found.putIfAbsent(address,ChapterLink(address,label))
        }
        return found.values.toList()
    }

    fun chapters(seriesUrl:String):List<ChapterLink>{
        val id=mangaId(seriesUrl)
        val found=LinkedHashMap<String,ChapterLink>()
        val limit=100
        var offset=0
        while(true){
            require(offset<=20_000){
                "MangaDex English chapter index exceeded safe pagination; no partial ZIP"
            }
            val route="/manga/$id/feed?translatedLanguage%5B%5D=en&"+
                "order%5Bchapter%5D=asc&limit=$limit&offset=$offset"
            val page=json(route)
            for(item in parseFeed(page))found.putIfAbsent(item.url,item)
            val returned=page.optJSONArray("data")?.length()
                ?:error("MangaDex returned an incomplete chapter page")
            val total=page.optInt("total",-1)
            require(total>=0){"MangaDex did not provide the chapter count"}
            offset+=returned
            if(offset>=total)break
            require(returned>0){
                "MangaDex pagination ended before all English chapters were enumerated"
            }
        }
        require(found.isNotEmpty()){
            "MangaDex title has no publicly readable English chapters"
        }
        return ChapterScraper.sort(found.values.toList())
    }

    /** Require the exact original-resolution filenames in the selected
     * chapter manifest. A dataSaver[] rendition is NOT a full original.
     */
    internal fun parseAtHome(response:JSONObject):List<String>{
        require(response.optString("result")=="ok"){
            "MangaDex did not verify the original chapter manifest"
        }
        val base=response.optString("baseUrl").trimEnd('/')
        val remote=URI(base)
        val h=remote.host.orEmpty().lowercase(Locale.ROOT)
        require(remote.scheme=="https" && remote.userInfo==null &&
            remote.query==null && remote.fragment==null &&
            remote.path.orEmpty() in listOf("","/") &&
            (h=="mangadex.network" || h.endsWith(".mangadex.network") ||
                h=="uploads.mangadex.org")){
            "MangaDex served an unrecognized media host"
        }
        val chapter=response.optJSONObject("chapter")
            ?:error("MangaDex did not provide chapter originals")
        val mdHash=chapter.optString("hash")
        require(hash.matches(mdHash)){"Invalid MangaDex chapter hash"}
        val originals=chapter.optJSONArray("data")
            ?:error("MangaDex offered no full-resolution image list")
        require(originals.length() in 1..1000){
            "MangaDex original chapter has no valid page count"
        }
        val result=ArrayList<String>(originals.length())
        for(i in 0 until originals.length()){
            val filename=originals.optString(i)
            require(name.matches(filename) && filename!=".." &&
                !filename.contains("..")){
                "Invalid original chapter page filename"
            }
            result.add(base+"/data/"+mdHash+"/"+filename)
        }
        require(result.distinct().size==result.size){
            "MangaDex repeated chapter image filenames"
        }
        return result
    }
    fun pageImages(chapterUrl:String):List<String>{
        val id=chapterId(chapterUrl)
        return parseAtHome(json("/at-home/server/$id",800_000))
    }
}
