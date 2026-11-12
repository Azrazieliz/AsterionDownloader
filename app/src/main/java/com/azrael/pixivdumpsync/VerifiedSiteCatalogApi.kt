package com.azrael.pixivdumpsync

import android.content.Context
import com.asterion.videoflow.NetworkRoute
import com.asterion.videoflow.EpornerOriginalSource
import com.azrael.galleryflow.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/**
 * Real provider-published listing endpoints. These are catalog previews, NOT
 * proof of playable original files. Original acquisition always goes through
 * the existing verified original transfer engine and explicit user consent.
 */
internal object VerifiedSiteCatalogApi {
    data class Result(val media:List<IndexedItem>,val next:String?)

    private fun trusted(source:String,target:String):Boolean =
        SourceIndexParser.safe(target) && SourceIndexParser.sameSite(source,target)

    internal fun eporner(json:String,source:String,area:String,
                         stamp:Long=System.currentTimeMillis()):Result {
        val data=JSONObject(json)
        val videos=data.optJSONArray("videos")?:JSONArray()
        val results=LinkedHashMap<String,IndexedItem>()
        for(i in 0 until minOf(videos.length(),100)){
            val row=videos.optJSONObject(i)?:continue
            val url=row.optString("url")
            val title=Jsoup.parse(row.optString("title")).text().trim().take(180)
            // EPORNER publishes both /video-ID/ and /hd-porn/ID/title.
            // The previous catalog parser silently dropped all hd-porn cards,
            // although the native player route correctly recognized them.
            if(!trusted(source,url) ||
                !com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url) ||
                IndexedMediaQuality.navigationTitle(title))continue
            val thumb=row.optJSONObject("default_thumb")?.optString("src")
                ?:row.optString("default_thumb")
            val image=thumb.takeIf{SearchRanking.validUrl(it)}
                ?:row.optJSONArray("thumbs")?.let{arr->
                    (0 until arr.length()).mapNotNull{idx->
                        arr.optJSONObject(idx)?.optString("src")
                            ?.takeIf{SearchRanking.validUrl(it)}
                    }.firstOrNull()
                }
            if(image.isNullOrBlank())continue
            // One publisher ID can appear under /hd-porn and /video aliases.
            // Preserve one identity; do not equate unrelated IDs by vague title.
            val identity=EpornerOriginalSource.canonicalUrl(url)?:url
            results.putIfAbsent(identity,IndexedItem(
                identity,title,image,source,area,"video",stamp,false))
        }
        val page=data.optInt("page",1)
        val total=data.optInt("total_pages",page)
        val next=if(page in 1 until total && page<1000)
            "https://www.eporner.com/api/v2/video/search/?per_page=50"+
                "&order=latest&thumbsize=big&query=all&page="+(page+1)
            else null
        return Result(results.values.toList(),next)
    }

    internal fun hentaiBros(json:String,source:String,area:String,
                            stamp:Long=System.currentTimeMillis()):Result {
        val posts=JSONArray(json)
        val result=LinkedHashMap<String,IndexedItem>()
        for(i in 0 until minOf(posts.length(),100)){
            val obj=posts.optJSONObject(i)?:continue
            if(obj.optString("status")!="publish" ||
                obj.optString("type")!="post")continue
            val link=obj.optString("link")
            val title=Jsoup.parse(obj.optJSONObject("title")
                ?.optString("rendered").orEmpty()).text().take(180)
            if(!trusted(source,link) || IndexedMediaQuality.navigationTitle(title))continue
            val images=obj.optJSONObject("yoast_head_json")
                ?.optJSONArray("og_image")?:JSONArray()
            val image=(0 until images.length()).mapNotNull{j->
                images.optJSONObject(j)?.optString("url")
                    ?.takeIf{SearchRanking.validUrl(it)}
            }.firstOrNull()?:continue
            // This is an actual published post with a provider-owned cover.
            // The post may require its separate authorized video extractor.
            result[link]=IndexedItem(link,title,image,source,area,"video",stamp,false)
        }
        return Result(result.values.toList(),null)
    }

    fun fetch(context:Context,source:IndexedSource,cursor:String?=null):Result?{
        val host=URI(source.url).host.orEmpty().lowercase().removePrefix("www.")
        if(source.area !in setOf("ADULT","HENTAI"))return null
        val api=when(host){
            "eporner.com"->cursor?.takeIf{it.startsWith(
                "https://www.eporner.com/api/v2/video/search/?")} ?:
                "https://www.eporner.com/api/v2/video/search/"+
                    "?per_page=50&order=latest&thumbsize=big&query=all&page=1"
            "hentaibros.net"->cursor?.takeIf{it.startsWith(
                "https://hentaibros.net/wp-json/wp/v2/posts?")} ?:
                "https://hentaibros.net/wp-json/wp/v2/posts?per_page=30&page=1"
            // 3DHentai serves official public WordPress JSON with validated
            // full post URLs and Yoast source-owned poster URLs. Do not
            // mistake category tiles or movie advertisements for originals.
            "3dhentaix.com"->cursor?.takeIf{it.startsWith(
                "https://3dhentaix.com/wp-json/wp/v2/posts?")} ?:
                "https://3dhentaix.com/wp-json/wp/v2/posts?per_page=30&page=1"
            else->return null
        }
        require(SourceIndexParser.safe(api) &&
            SourceIndexParser.sameSite(source.url,api)){"Untrusted provider API"}
        val response=Jsoup.connect(api).userAgent(HttpClient.USER_AGENT)
            .ignoreContentType(true).ignoreHttpErrors(true)
            .followRedirects(false).timeout(14_000).maxBodySize(4_000_000)
            .apply{NetworkRoute.javaProxy(context,source.url)?.let{proxy(it)}}
            .execute()
        require(response.statusCode() in 200..299){
            "Provider catalog API HTTP "+response.statusCode()
        }
        return if(host=="eporner.com"){
            eporner(response.body(),source.url,source.area)
        }else{
            val parsed=hentaiBros(response.body(),source.url,source.area)
            // WordPress API supports numbered catalog pages without requiring
            // a manually entered history floor. Never invent volume numbers.
            val current=Regex("""[?&]page=(\d+)""").find(api)
                ?.groupValues?.get(1)?.toIntOrNull()?:1
            // Provider-owned published posts, never navigation/category URLs.
            val verified=parsed.media.filter{
                com.asterion.videoflow.VideoSiteProfiles.isVideoPage(it.url)
            }
            parsed.copy(media=verified,next=if(parsed.media.size>=30 && current<200)
                "https://"+host+"/wp-json/wp/v2/posts?per_page=30&page="+(current+1)
                else null)
        }
    }
}
