package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Context
import com.azrael.galleryflow.HttpClient
import com.azrael.galleryflow.SiteSession
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Local cache of real, public gallery covers for unsynced Kyora creator listings.
 * The native GalleryFlow gallery DB remains authoritative once a site is backfilled.
 */
object KyoraCreatorPreviews {
    data class Preview(val url:String,val cover:String,val title:String)
    data class Snapshot(val entries:List<Preview>,val updated:Long,val error:String?)
    private const val PREF="asterion_kyora_site_previews"
    private val inflight=ConcurrentHashMap.newKeySet<String>()
    private val pool=Executors.newFixedThreadPool(5)
    fun cached(context:Context,source:String):Snapshot?=runCatching {
        val raw=context.getSharedPreferences(PREF,0).getString(source,null)?:return null
        val obj=JSONObject(raw)
        val arr=obj.optJSONArray("entries")?:JSONArray()
        Snapshot((0 until minOf(24,arr.length())).mapNotNull{i->
            arr.optJSONObject(i)?.let{
                Preview(it.optString("url"),it.optString("cover"),it.optString("title"))
            }
        },obj.optLong("updated"),obj.optString("error").takeIf{it.isNotBlank()})
    }.getOrNull()
    internal fun parse(html:String,base:String):List<Preview>{
        val doc=Jsoup.parse(html,base)
        // www/mobile/locale subdomains are the same approved provider.
        // Do not reject legitimate links when the site's landing URL redirects
        // from fr.example.com to www.example.com.
        val result=LinkedHashMap<String,Preview>()
        val sections=doc.select("article a:has(img),.gallery a:has(img),"+
            ".post a:has(img),.posts a:has(img),.album a:has(img),"+
            ".entry a:has(img),.grid a:has(img),main a:has(img),"+
            ".video a:has(img),.videos a:has(img),.video-item a:has(img),"+
            ".video-card a:has(img),.thumb a:has(img),.thumbs a:has(img),"+
            ".video-thumb a:has(img),.clip a:has(img),.movie a:has(img),"+
            ".items a:has(img),.post-item a:has(img)")
        val anchors=if(sections.isEmpty())doc.select("a:has(img)") else sections
        for(anchor in anchors.take(1500)){
            val page=anchor.absUrl("href")
            if(!SearchRanking.validUrl(page))continue
            val target=runCatching{URI(page)}.getOrNull()?:continue
            if(target.scheme!="https" ||
                !SourceIndexParser.sameSite(base,page))continue
            val img=anchor.selectFirst("img")?:continue
            val cover=SitePreviewImage.best(img)?:continue
            val imgPath=URI(cover).path.lowercase()
            if(listOf("logo","icon","avatar","sprite","banner","pixel","advert")
                .any{imgPath.contains(it)})continue
            val title=anchor.attr("title").ifBlank{img.attr("alt")}
                .ifBlank{anchor.text()}
                .ifBlank{anchor.closest("article,.post,.video,.item")?.selectFirst("h2,h3")?.text().orEmpty()}
                .ifBlank{runCatching{
                    URI(page).path.orEmpty().trim('/').substringAfterLast('/')
                        .replace('-', ' ').replace('_',' ')
                }.getOrDefault("")}
                .trim().take(100).ifBlank{"Untitled media"}
            if(IndexedMediaQuality.navigationTitle(title))continue
            result.putIfAbsent(page,Preview(page,cover,title))
            if(result.size>=24)break
        }
        return result.values.toList()
    }
    fun load(a:Activity,url:String,force:Boolean=false,
             onReady:(Snapshot)->Unit){
        if(!force)cached(a,url)?.let{onReady(it)}
        if(!SourceIndexParser.safe(url))return
        val stored=cached(a,url)
        if(!force && stored!=null && System.currentTimeMillis()-stored.updated<
            (if(stored.error==null)12L*60*60*1000 else 2L*60*60*1000))return
        if(!inflight.add(url))return
        pool.execute{
            val snapshot=runCatching{
                var current=url
                var page:org.jsoup.nodes.Document?=null
                for(hop in 0..4){
                    require(SourceIndexParser.safe(current) &&
                        SourceIndexParser.sameSite(url,current)){
                        "Provider redirect moved to a different domain; verify updated source"
                    }
                    val response=Jsoup.connect(current).timeout(15000)
                        .maxBodySize(2_000_000).userAgent(HttpClient.USER_AGENT)
                        .followRedirects(false).ignoreHttpErrors(true)
                        .apply{SiteSession.cookie(current)?.let{header("Cookie",it)}}
                        .execute()
                    if(response.statusCode() in 300..399){
                        val location=response.header("Location")
                            ?:error("Provider redirect has no destination")
                        val next=URI(current).resolve(location).toString()
                        require(SourceIndexParser.safe(next) &&
                            SourceIndexParser.sameSite(url,next)){
                            "Provider redirected to a different website; source ownership unverified"
                        }
                        current=next
                    }else{
                        require(response.statusCode() in 200..299){
                            "Provider HTTP "+response.statusCode()+
                                "; connect an authorized account if required"
                        }
                        page=response.parse()
                        break
                    }
                }
                val document=page?:error("Too many provider redirects")
                val items=parse(document.outerHtml(),document.location())
                if(items.isEmpty())error("No gallery thumbnails exposed by listing")
                Snapshot(items,System.currentTimeMillis(),null)
            }.getOrElse{
                Snapshot(stored?.entries.orEmpty(),System.currentTimeMillis(),
                    (it.message?:"Source not available").take(160))
            }
            val obj=JSONObject().apply{
                put("updated",snapshot.updated);put("error",snapshot.error?:"")
                put("entries",JSONArray().apply{
                    for(item in snapshot.entries)put(JSONObject().apply{
                        put("url",item.url);put("cover",item.cover);put("title",item.title)
                    })
                })
            }
            a.getSharedPreferences(PREF,0).edit().putString(url,obj.toString()).apply()
            inflight.remove(url)
            a.runOnUiThread{if(!a.isFinishing&&!a.isDestroyed)onReady(snapshot)}
        }
    }
}
