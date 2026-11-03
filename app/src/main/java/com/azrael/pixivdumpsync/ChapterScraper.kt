package com.azrael.pixivdumpsync

import android.content.Context
import android.webkit.WebSettings
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

data class ChapterLink(val url:String,val title:String)

/** Chapter lists and readers: preserve chapter numbers, fractions and original page order. */
object ChapterScraper {
    // Avoid repeating a guaranteed-to-fail non-browser request for every chapter.
    private val browserOnlyUntil=java.util.concurrent.ConcurrentHashMap<String,Long>()
    private val chapterNumber=Regex(
        "(?i)(?:chapter|chapitre|episode|ch[.-]?|ep[.-]?)[\\s_/-]*(\\d+(?:[._]\\d+)?)"
    )
    private val pathChapter=Regex("(?i)/(?:chapter|chapitre|chap|ch|episode)[-_]?(\\d+(?:[._]\\d+)?)")
    private fun numberFrom(text:String):Double? =
        chapterNumber.find(text)?.groupValues?.get(1)?.replace('_','.')
            ?.toDoubleOrNull()
    fun naturalKey(text:String):Double=
        numberFrom(text)?:pathChapter.find(text)?.groupValues?.get(1)
            ?.replace('_','.')?.toDoubleOrNull()?:Double.MAX_VALUE
    fun sort(items:List<ChapterLink>):List<ChapterLink> =
        items.distinctBy{it.url.trimEnd('/')}.sortedWith(
            compareBy<ChapterLink>{
                val titleNumber=naturalKey(it.title)
                if(titleNumber!=Double.MAX_VALUE)titleNumber else naturalKey(it.url)
            }.thenBy{it.title.lowercase(Locale.ROOT)}.thenBy{it.url})
    fun isSingleChapter(url:String):Boolean =
        pathChapter.containsMatchIn(URI(url).path.orEmpty()) ||
            SushiScanChapters.isReader(url)
    private fun read(url:String,context:Context?=null):Document{
        val agent=context?.let{runCatching{WebSettings.getDefaultUserAgent(it)}
            .getOrNull()}?.takeIf{it.isNotBlank()}
            ?: "Mozilla/5.0 (Linux; Android 17; Mobile) AppleWebKit/537.36 Chrome/131.0"
        val host=URI(url).host.orEmpty().lowercase().removePrefix("www.")
        val session=context!=null && com.azrael.galleryflow.SiteSession.bound(context,url)
        if(session && (browserOnlyUntil[host]?:0L)>System.currentTimeMillis())
            return Jsoup.parse(BrowserPageFetch.html(requireNotNull(context),url),url)
        val connection=Jsoup.connect(url)
            .userAgent(agent)
            .header("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language","en-US,en;q=0.9")
            .timeout(30000).maxBodySize(16_000_000).followRedirects(true)
            .ignoreHttpErrors(true)
        com.azrael.galleryflow.SiteSession.cookie(url)?.let { requestCookie ->
            connection.header("Cookie", requestCookie)
        }
        val result=try { connection.execute() }catch(network:Exception){
            if(session){
                browserOnlyUntil[host]=System.currentTimeMillis()+600_000L
                return Jsoup.parse(BrowserPageFetch.html(requireNotNull(context),url),url)
            }
            throw network
        }
        val doc=result.parse()
        val accessible=result.statusCode() in 200..299 &&
            !doc.title().contains("Just a moment",true) &&
            !doc.title().contains("Attention required",true) &&
            !doc.text().contains("Enable JavaScript and cookies to continue",true)
        if(accessible)return doc
        if(session){
            browserOnlyUntil[host]=System.currentTimeMillis()+600_000L
            return Jsoup.parse(BrowserPageFetch.html(requireNotNull(context),url),url)
        }
        if(result.statusCode()==403||doc.title().contains("Just a moment",true))
            throw IllegalStateException("Browser verification required (HTTP "+
                result.statusCode()+"). Open source browser and save a valid session.")
        throw IllegalStateException("Series page returned HTTP "+result.statusCode())
    }
    private fun allowed(raw:String):Boolean=SearchRanking.validUrl(raw)
    private fun clean(u:String):String=u.substringBefore('#').trimEnd('/')+"/"
    private fun chapterTitle(anchor:org.jsoup.nodes.Element,url:String):String{
        val label=anchor.text().trim().ifBlank{anchor.attr("title").trim()}
        val num=numberFrom(label) ?: pathChapter.find(URI(url).path.orEmpty())
            ?.groupValues?.get(1)?.replace('_','.')?.toDoubleOrNull()
        return if(num!=null) "Chapter "+num.toString().removeSuffix(".0") else label.ifBlank{"Chapter"}
    }
    fun chapters(seriesUrl:String,context:Context?=null):List<ChapterLink>{
        require(allowed(seriesUrl)){"Provide an HTTP(S) chapter or series URL"}
        if(NhentaiChapters.isGallery(seriesUrl)){
            val gallery=read(seriesUrl,context)
            val full=NhentaiChapters.pages(gallery,seriesUrl)
            require(full.isNotEmpty()){
                "NHentai gallery has no complete page listing; no ZIP will be written"
            }
            return full
        }
        if(SushiScanChapters.isSeries(seriesUrl)){
            val page=read(seriesUrl,context)
            val chapters=SushiScanChapters.chapters(page,seriesUrl)
            require(chapters.isNotEmpty()){
                "SushiScan series index did not expose its chapter/volume links; no partial ZIP"
            }
            return chapters
        }
        if(isSingleChapter(seriesUrl))
            return listOf(ChapterLink(seriesUrl,"Chapter "+
                (pathChapter.find(URI(seriesUrl).path.orEmpty())
                    ?.groupValues?.get(1)?.replace('_','.')?:"1")))
        // The old /series/ URL redirects to /serie/ on Toonily; use
        // the resolved canonical URL for the prefix, not the user's old link.
        val initial=read(seriesUrl,context)
        val base=URI(initial.location())
        val prefix=base.path.orEmpty().trimEnd('/')+"/"
        val root=base.host?.removePrefix("www.")
        val collected=linkedMapOf<String,ChapterLink>()
        var next:String?=initial.location()
        var first:Document?=initial
        val visited=mutableSetOf<String>()
        while(next!=null){
            if(!visited.add(clean(next)))break
            require(visited.size<=150){"Over 150 index pages; cannot establish full chapter list"}
            val doc=first ?: read(next,context)
            first=null
            for(a in doc.select("a[href], option[value]")){
                val href=if(a.tagName()=="option")a.attr("value")else a.attr("href")
                val link=runCatching{URI(doc.location()).resolve(href).toString()}
                    .getOrNull()?:continue
                if(!allowed(link))continue
                val uri=URI(link)
                if(uri.host?.removePrefix("www.")!=root)continue
                if(!uri.path.orEmpty().startsWith(prefix) ||
                    !pathChapter.containsMatchIn(uri.path.orEmpty()))continue
                val canonical=clean(link)
                collected.putIfAbsent(canonical,
                    ChapterLink(canonical,chapterTitle(a,link)))
            }
            val nextTag=doc.selectFirst(
                ".pagination a.next[href], a.page-numbers.next[href], a[rel=next][href]"
            )
            val found=nextTag?.let{
                runCatching{URI(doc.location()).resolve(it.attr("href")).toString()}.getOrNull()
            }
            next=found?.takeIf{allowed(it)&&URI(it).host?.removePrefix("www.")==root &&
                !visited.contains(clean(it))}
        }
        require(collected.isNotEmpty()){
            "No chapter links found on the series page; refusing to create an incomplete ZIP."
        }
        return sort(collected.values.toList())
    }
    fun parsePageImages(doc:Document):List<String>{
        val nodes=doc.select(".reading-content img, .chapter-content img, #reader img, "+
            ".page-chapter img, .container-chapter-reader img, #readerarea img, "+
            ".chapter-images img, img.wp-manga-chapter-img, "+
            "img[data-original], img[data-src], img[data-lazy-src]")
        val fallback=if(nodes.isEmpty())doc.select("article img, main img")else nodes
        val urls=linkedSetOf<String>()
        for(node in fallback){
            val raw=listOf("data-original","data-src","data-lazy-src",
                "data-lazy","data-full","data-cfsrc","src")
                .map{node.attr(it)}.firstOrNull{
                    it.isNotBlank() && !it.startsWith("data:") && !it.startsWith("blob:")
                }?:node.attr("srcset").split(',').lastOrNull()?.trim()?.substringBefore(' ')
                ?:continue
            val resolved=runCatching{URI(doc.location()).resolve(raw.trim()).toString()}
                .getOrNull()?:continue
            if(allowed(resolved) &&
                !Regex("(?i)(avatar|icon|emoji|logo|loading|spinner)").containsMatchIn(resolved))
                urls.add(resolved)
        }
        return urls.toList()
    }
    fun pageImages(chapterUrl:String,context:Context?=null):List<String>{
        val doc=read(chapterUrl,context)
        val images=if(NhentaiChapters.isPage(chapterUrl))
            NhentaiChapters.pageImage(doc)
        else parsePageImages(doc)
        require(images.isNotEmpty()){"No readable chapter images at $chapterUrl"}
        return images
    }
}
