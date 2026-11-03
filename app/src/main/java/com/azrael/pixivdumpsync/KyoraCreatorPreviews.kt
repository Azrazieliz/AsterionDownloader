package com.azrael.pixivdumpsync

import android.app.Activity
import android.content.Context
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
    private val pool=Executors.newFixedThreadPool(2)
    fun cached(context:Context,source:String):Snapshot?=runCatching {
        val raw=context.getSharedPreferences(PREF,0).getString(source,null)?:return null
        val obj=JSONObject(raw)
        val arr=obj.optJSONArray("entries")?:JSONArray()
        Snapshot((0 until minOf(8,arr.length())).mapNotNull{i->
            arr.optJSONObject(i)?.let{
                Preview(it.optString("url"),it.optString("cover"),it.optString("title"))
            }
        },obj.optLong("updated"),obj.optString("error").takeIf{it.isNotBlank()})
    }.getOrNull()
    internal fun parse(html:String,base:String):List<Preview>{
        val doc=Jsoup.parse(html,base)
        val host=URI(base).host.lowercase().removePrefix("www.")
        val result=LinkedHashMap<String,Preview>()
        val sections=doc.select("article a:has(img),.gallery a:has(img),"+
            ".post a:has(img),.posts a:has(img),.album a:has(img),"+
            ".entry a:has(img),.grid a:has(img),main a:has(img)")
        val anchors=if(sections.isEmpty())doc.select("a:has(img)") else sections
        for(anchor in anchors.take(200)){
            val page=anchor.absUrl("href")
            if(!SearchRanking.validUrl(page))continue
            val target=runCatching{URI(page)}.getOrNull()?:continue
            if(target.scheme!="https" ||target.host.lowercase().removePrefix("www.")!=host)
                continue
            val img=anchor.selectFirst("img")?:continue
            val cover=sequenceOf("data-original","data-src","data-lazy-src","src")
                .map{img.absUrl(it)}.firstOrNull{SearchRanking.validUrl(it)}
                ?:continue
            val imgPath=URI(cover).path.lowercase()
            if(listOf("logo","icon","avatar","sprite","banner","pixel","advert")
                .any{imgPath.contains(it)})continue
            val title=anchor.attr("title").ifBlank{img.attr("alt")}
                .ifBlank{anchor.text()}.trim().take(100)
                .ifBlank{"Gallery"}
            result.putIfAbsent(page,Preview(page,cover,title))
            if(result.size>=6)break
        }
        return result.values.toList()
    }
    fun load(a:Activity,url:String,onReady:(Snapshot)->Unit){
        cached(a,url)?.let{onReady(it)}
        if(!SourceIndexParser.safe(url))return
        val stored=cached(a,url)
        if(stored!=null && System.currentTimeMillis()-stored.updated<
            (if(stored.error==null)12L*60*60*1000 else 2L*60*60*1000))return
        if(!inflight.add(url))return
        pool.execute{
            val snapshot=runCatching{
                val page=Jsoup.connect(url).timeout(15000).maxBodySize(2_000_000)
                    .userAgent("Mozilla/5.0 (Android 17) AsterionDownloader")
                    .get()
                require(page.location().startsWith("https://")){"Source redirected to HTTP"}
                val items=parse(page.outerHtml(),page.location())
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
