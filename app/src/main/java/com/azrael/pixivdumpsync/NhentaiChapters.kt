package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import java.net.URI

/** NHentai's two specifically approved gallery hosts. Preserve full page ordering
 * and use the existing native comics ZIP pipeline, never CBZ/PDF.
 */
object NhentaiChapters {
    private val hosts=setOf("nhentai.net","nhentai.xxx")
    private val galleryPath=Regex("""^/g/(\d{1,12})/?$""")
    internal fun galleryId(raw:String):String?=runCatching{
        val uri=URI(raw)
        val host=uri.host.lowercase().removePrefix("www.")
        if(uri.scheme!="https" || host !in hosts || uri.userInfo!=null)null
        else galleryPath.matchEntire(uri.path)?.groupValues?.get(1)
    }.getOrNull()
    fun isGallery(url:String)=galleryId(url)!=null

    fun pages(doc:Document,seriesUrl:String):List<ChapterLink> {
        val series=URI(seriesUrl)
        val id=galleryId(seriesUrl)?:return emptyList()
        val byPage=sortedMapOf<Int,ChapterLink>()
        for(anchor in doc.select("#thumbnail-container a[href],"+
            ".gallerythumb a[href],.thumb-container a[href],"+
            "#gallery a[href*='/g/']")){
            val dest=runCatching{series.resolve(anchor.attr("href"))}.getOrNull()
                ?:continue
            if(dest.scheme!="https" || !dest.host.equals(series.host,true))continue
            val match=Regex("^/g/"+Regex.escape(id)+"/(\\d{1,6})/?$")
                .matchEntire(dest.path)?:continue
            val n=match.groupValues[1].toIntOrNull()?:continue
            if(n<1||n>20000)continue
            byPage[n]=ChapterLink(dest.toString(),"Page "+n)
        }
        if(byPage.isEmpty())return emptyList()
        require(byPage.firstKey()==1 && byPage.lastKey()==byPage.size){
            "Missing NHentai gallery page links; refusing incomplete ZIP"
        }
        val count=Regex("""(?i)(\d{1,6})\s+pages\b""")
            .find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        if(count!=null)require(count==byPage.size){
            "Gallery declares "+count+" pages but only "+byPage.size+" were indexed"
        }
        return byPage.values.toList()
    }
}
