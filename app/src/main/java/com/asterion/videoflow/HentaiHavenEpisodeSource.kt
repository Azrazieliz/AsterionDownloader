package com.asterion.videoflow

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Modern HentaiHaven publishes /watch/<series>/episode-<n> as individual
 * videos; /watch/<series> is only a series collection. The selected episode's
 * own VideoObject JSON-LD names its original HLS as contentUrl.
 *
 * Never interpret card thumbnails, RSC recommendation previewUrl MP4s, a
 * series aggregate, or arbitrary third-party video URLs as the original.
 */
internal object HentaiHavenEpisodeSource {
    private val series=Regex("""^/watch/[a-z0-9][a-z0-9-]{3,220}/?$""",
        RegexOption.IGNORE_CASE)
    private val episode=Regex(
        """^/watch/[a-z0-9][a-z0-9-]{3,220}/episode-[0-9]{1,5}/?$""",
        RegexOption.IGNORE_CASE)

    private fun uri(url:String):URI?=runCatching{URI(url)}.getOrNull()
    private fun host(url:String):String=
        uri(url)?.host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    private fun selected(url:String,path:Regex):Boolean {
        val u=uri(url)?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            u.rawFragment==null && u.rawQuery.isNullOrBlank() &&
            host(url)=="hentaihaven.xxx" &&
            path.matches(u.path.orEmpty())
    }
    fun isSeries(url:String):Boolean=selected(url,series)
    fun isEpisode(url:String):Boolean=selected(url,episode)

    /** Return only first-party links with an episode segment immediately
     * beneath the current selected series. Recommendation cards cannot be
     * substituted for the owner's chosen collection.
     */
    fun episodes(doc:Document,seriesUrl:String):List<String>{
        if(!isSeries(seriesUrl))return emptyList()
        val prefix=uri(seriesUrl)?.path.orEmpty().trimEnd('/')
        val found=LinkedHashSet<String>()
        for(link in doc.select("a[href]").take(2000)){
            if(link.closest("aside,.related,.recommendations,.ads,.sidebar")!=null)
                continue
            val dest=runCatching{
                uri(seriesUrl)!!.resolve(link.attr("href")).toString()
            }.getOrNull()?:continue
            if(!isEpisode(dest))continue
            if(uri(dest)?.path.orEmpty().trimEnd('/')
                .startsWith(prefix+"/episode-"))found+=dest
        }
        return found.toList()
    }

    private fun videoObject(obj:Any?,out:MutableSet<String>,chosen:String,
                            depth:Int=0){
        if(depth>8)return
        when(obj){
            is JSONObject->{
                val typ=obj.opt("@type")
                val actual=when(typ){
                    is String->typ=="VideoObject"
                    is JSONArray->(0 until typ.length()).any{
                        typ.optString(it)=="VideoObject"}
                    else->false
                }
                if(actual){
                    val declared=obj.optString("url")
                    if(declared.isNotBlank() && declared.trimEnd('/') !=
                        chosen.trimEnd('/'))return
                    val link=obj.optString("contentUrl")
                    if(isApprovedHls(link))out+=link
                }
                val keys=obj.keys()
                while(keys.hasNext()){
                    val key=keys.next()
                    val item=obj.opt(key)
                    if(item is JSONObject || item is JSONArray)
                        videoObject(item,out,chosen,depth+1)
                }
            }
            is JSONArray->for(i in 0 until minOf(obj.length(),100))
                videoObject(obj.opt(i),out,chosen,depth+1)
        }
    }
    internal fun isApprovedHls(url:String):Boolean {
        val u=uri(url)?:return false
        if(u.scheme!="https" || u.userInfo!=null || u.port!=-1 ||
            u.rawFragment!=null || !u.rawQuery.isNullOrBlank() ||
            host(url)!="octopusmanifest.org")return false
        return u.path.orEmpty().matches(
            Regex("""(?i)^/[a-zA-Z0-9_./-]{8,500}\.m3u8$""")) &&
            u.path.orEmpty().count{it=='/'}==2 &&
            !FullLengthVideoPolicy.isExplicitPreview("",url)
    }
    /** The selected episode's own JSON-LD is stronger evidence than MP4
     * previewUrl strings included by the Next.js recommendation index.
     */
    fun publishedHls(doc:Document,episodeUrl:String):String? {
        if(!isEpisode(episodeUrl))return null
        val found=LinkedHashSet<String>()
        for(script in doc.select("script[type=application/ld+json]").take(20)){
            val body=script.data().ifBlank{script.html()}
            if(body.length !in 60..400_000)continue
            val parsed=runCatching{JSONTokener(body).nextValue()}
                .getOrNull()?:continue
            videoObject(parsed,found,episodeUrl)
        }
        return found.singleOrNull()
    }
}
