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
    fun isOriginalImage(url:String):Boolean=runCatching{
        val uri=URI(url)
        uri.scheme=="https" &&
            uri.host.lowercase() in setOf("i.nhentai.net") &&
            Regex("^/galleries/[0-9]{1,20}/[0-9]{1,6}\\.(jpg|png|webp|gif)$")
                .matches(uri.path)
    }.getOrDefault(false)
    fun isPage(url:String):Boolean=runCatching{
        val uri=URI(url)
        uri.scheme=="https" && uri.host.lowercase().removePrefix("www.") in hosts &&
            Regex("""^/g/\d{1,12}/\d{1,6}/?$""").matches(uri.path)
    }.getOrDefault(false)
    fun pageImage(doc:Document):List<String>{
        val image=doc.selectFirst("#image-container img#image,"+
            "#image-container img,.reader img#image,img#image")?:
            throw IllegalStateException("No full-resolution gallery page image")
        for(attr in listOf("data-original","data-src","data-lazy-src","src")){
            val url=image.absUrl(attr)
            if(SearchRanking.validUrl(url)&&url.startsWith("https://"))
                return listOf(url)
        }
        throw IllegalStateException("Full-resolution gallery page URL unavailable")
    }

    fun pages(doc:Document,seriesUrl:String):List<ChapterLink> {
        val series=URI(seriesUrl)
        val id=galleryId(seriesUrl)?:return emptyList()
        val byPage=sortedMapOf<Int,ChapterLink>()
        // Reader links may be in a custom container or generated with a
        // different gallery stylesheet. The exact gallery ID/page path,
        // not a fragile class selector, decides whether a link is valid.
        for(anchor in doc.select("a[href]")){
            val dest=runCatching{series.resolve(anchor.attr("href"))}.getOrNull()
                ?:continue
            if(dest.scheme!="https" || !dest.host.equals(series.host,true))continue
            val match=Regex("^/g/"+Regex.escape(id)+"/(\\d{1,6})/?$")
                .matchEntire(dest.path)?:continue
            val n=match.groupValues[1].toIntOrNull()?:continue
            if(n<1||n>20000)continue
            byPage[n]=ChapterLink(dest.toString(),"Page "+n)
        }
        // NHentai's gallery-data JSON is a canonical complete inventory of
        // image types. It can be used even when the thumbnail navigation is
        // rendered asynchronously, avoiding a false "no page listing" error.
        // Only the documented nhentai.net image endpoint is constructed;
        // clone/mirror CDNs are not guessed.
        if(byPage.isEmpty() && series.host.equals("nhentai.net",true)){
            val raw=doc.selectFirst("[data-gallery]")?.attr("data-gallery")
                .orEmpty()
            if(raw.isNotBlank()){
                val data=runCatching{org.json.JSONObject(raw)}.getOrNull()
                val media=data?.optString("media_id").orEmpty()
                val pages=data?.optJSONObject("images")?.optJSONArray("pages")
                val number=data?.optInt("num_pages",0)?:0
                if(media.matches(Regex("[0-9]{1,20}")) &&
                    pages!=null && pages.length() in 1..1000 &&
                    (number==0 || number==pages.length())){
                    val links=(0 until pages.length()).mapNotNull{i->
                        val type=pages.optJSONObject(i)?.optString("t")
                        val ext=when(type){
                            "j"->"jpg";"p"->"png";"g"->"gif";"w"->"webp"
                            else->null
                        } ?:return@mapNotNull null
                        ChapterLink(
                            "https://i.nhentai.net/galleries/"+media+"/"+
                                (i+1)+"."+ext,"Page "+(i+1))
                    }
                    if(links.size==pages.length())return links
                }
            }
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
