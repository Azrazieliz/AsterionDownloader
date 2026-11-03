package com.azrael.pixivdumpsync

import com.azrael.galleryflow.SiteSession
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Resolve a user-accessible signed file endpoint by its response headers.
 * A HEAD request does not download the file. No authentication or entitlement is forged.
 */
internal object AuthorizedEndpointInspector {
    internal fun filename(contentDisposition:String?,fallbackUrl:String):String {
        val header=contentDisposition.orEmpty()
        val encoded=Regex("""(?i)filename\*\s*=\s*(?:UTF-8''|"UTF-8'')([^;"\s]+)""")
            .find(header)?.groupValues?.get(1)
        val plain=Regex("""(?i)filename\s*=\s*(?:"([^"]+)"|([^;\s]+))""")
            .find(header)
        val chosen=encoded?.let{runCatching{URLDecoder.decode(it,"UTF-8")}.getOrNull()}
            ?:plain?.groupValues?.drop(1)?.firstOrNull{it.isNotBlank()}
            ?:URI(fallbackUrl).path.substringAfterLast('/')
        return chosen.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^\\p{L}\\p{N}._-]"),"_").take(100)
            .ifBlank{"original"}
    }
    internal fun mediaType(contentType:String?,filename:String):String {
        val mime=contentType.orEmpty().substringBefore(';').lowercase(Locale.ROOT).trim()
        val byFile=AuthorizedOriginalResolver.mediaType(filename)
        if(byFile.isNotBlank())return byFile
        return when(mime){
            "application/epub+zip"->"epub"
            "video/mp4","video/webm","video/quicktime"->"video"
            "image/png","image/jpeg","image/webp","image/gif"->"image"
            "model/gltf-binary","model/gltf+json"->"model"
            "application/vnd.android.package-archive"->"game"
            "application/x-bittorrent"->"game"
            "application/zip","application/x-7z-compressed"->"archive"
            else->""
        }
    }
    private fun accepts(hint:String,type:String):Boolean=when(hint.uppercase(Locale.ROOT)){
        "BOOKS"->type=="epub"
        "COMICS"->type=="archive"
        "ASSETS"->type in setOf("model","archive")
        "GAMES"->type in setOf("game","archive")
        "ADULT","HENTAI","VIDEOS","CREATORS"->type=="video"
        "COSPLAY","ARTWORK"->type=="image"
        else->type.isNotBlank()
    }
    fun inspect(url:String,hint:String):AccessibleOriginal? {
        if(!SourceIndexParser.safe(url))return null
        val connection=(URL(url).openConnection() as HttpURLConnection).apply{
            requestMethod="HEAD"
            connectTimeout=9000
            readTimeout=9000
            instanceFollowRedirects=false
            setRequestProperty("Accept","*/*")
            setRequestProperty("User-Agent","Mozilla/5.0 (Android) AsterionDownloader/1.3")
            SiteSession.cookie(url)?.let{setRequestProperty("Cookie",it)}
        }
        return try{
            when(connection.responseCode){
                // HEAD may be unsupported even while an authorized GET works.
                // Do not infer account access from these provisional responses.
                401,402,403,404,405,410,429->null
                in 200..299->{
                    val name=filename(connection.getHeaderField("Content-Disposition"),url)
                    val type=mediaType(connection.contentType,name)
                    if(!accepts(hint,type))return null
                    AccessibleOriginal(url,if(name.contains('.'))name else name+"."+
                        when(type){
                            "epub"->"epub";"video"->"mp4";"image"->"png"
                            "archive"->"zip";"game"->"apk";else->"glb"
                        },type,url)
                }
                else->null
            }
        }finally{connection.disconnect()}
    }
}
