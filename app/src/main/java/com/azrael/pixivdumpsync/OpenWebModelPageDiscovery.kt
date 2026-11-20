package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Recover genuine downloadable model files from indexed model DETAIL pages,
 * not just result anchors ending in .glb/.obj. A paid landing page or a
 * generic HTML link is never reported as a downloadable original.
 * No websites are registered or subscribed to by this discovery.
 */
internal object OpenWebModelPageDiscovery {
    private val formats=setOf("glb","gltf","fbx","obj","blend","stl",
        "dae","usd","usdz","ply","3mf","abc","unitypackage","vrm","pmx")
    private fun uri(value:String)=runCatching{URI(value)}.getOrNull()
    private fun approved(address:String):Boolean{
        val link=uri(address)?:return false
        return link.scheme=="https" && link.host!=null &&
            link.userInfo==null && link.port==-1 &&
            link.rawFragment==null && SearchRanking.validUrl(address) &&
            !ProviderRetirement.isRetired(address)
    }
    private fun unwrapped(raw:String):String {
        val link=uri(raw)?:return raw
        if(link.host.orEmpty().endsWith("duckduckgo.com") &&
            link.path=="/l/")return link.rawQuery.orEmpty().split('&')
                .firstOrNull{it.startsWith("uddg=")}
                ?.substringAfter('=')?.let{
                    runCatching{URLDecoder.decode(it,"UTF-8")}.getOrNull()
                }?:raw
        return raw
    }
    fun landingLinks(html:String,base:String,engine:String,term:String):List<String>{
        val document=Jsoup.parse(html,base)
        val anchors=if(engine=="DuckDuckGo")
            document.select("a.result__a[href]")
        else document.select("li.b_algo h2 a[href]")
        val found=LinkedHashSet<String>()
        for(anchor in anchors.take(75)){
            val label=anchor.text().trim()
            if(!SearchExactness.matches(term,label))continue
            val address=unwrapped(anchor.absUrl("href").ifBlank{
                anchor.attr("href")})
            if(!approved(address))continue
            val parsed=uri(address)?:continue
            val host=parsed.host.orEmpty().lowercase(Locale.ROOT)
            if(host in setOf("www.bing.com","bing.com",
                    "html.duckduckgo.com","duckduckgo.com"))continue
            // A direct original is handled by the existing parser. Only
            // actual model detail pages are inspected for file links.
            if(fileExtension(address)!=null)continue
            found+=address
            if(found.size>=12)break
        }
        return found.toList()
    }
    private fun fileExtension(address:String):String?{
        val path=uri(address)?.path.orEmpty().substringAfterLast('/')
        return path.substringAfterLast('.',missingDelimiterValue="")
            .lowercase(Locale.ROOT).takeIf{it in formats}
    }
    private fun directOriginal(raw:String):String?{
        val address=uri(raw)?:return null
        val extension=fileExtension(raw)?:return null
        if(!approved(raw))return null
        val parts=address.path.orEmpty().trim('/').split('/')
        // A GitHub blob page is HTML, not a model original. Transform only
        // a verified public blob URL to the matching raw.githubusercontent.
        if(address.host.equals("github.com",true) &&
            parts.size>=5 && parts[2]=="blob"){
            val rawFile="https://raw.githubusercontent.com/"+
                listOf(parts[0],parts[1]).plus(parts.drop(3)).joinToString("/")
            return rawFile.takeIf{approved(it) && fileExtension(it)==extension}
        }
        return raw
    }
    fun originals(html:String,page:String,term:String):
            List<OpenWebModelSearch.Candidate>{
        if(!approved(page))return emptyList()
        val document=Jsoup.parse(html,page)
        val title=document.selectFirst("h1")?.text()
            ?.takeIf{it.isNotBlank()}?:document.title()
        if(!SearchExactness.matches(term,title))return emptyList()
        val poster=document.selectFirst(
            "meta[property=og:image][content],meta[name=twitter:image][content]")
            ?.attr("content")?.takeIf{it.isNotBlank()}?.let{raw->
                runCatching{URI(page).resolve(raw).toString()}.getOrNull()
                    ?.takeIf{approved(it)}
            }
        val out=LinkedHashMap<String,OpenWebModelSearch.Candidate>()
        val anchors=document.select(
            "main a[href],article a[href],a[download][href],"+
            ".downloads a[href],.download a[href]")
        for(anchor in anchors.take(250)){
            val absolute=anchor.absUrl("href").ifBlank{
                uri(page)?.resolve(anchor.attr("href"))?.toString().orEmpty()
            }
            val direct=directOriginal(absolute)?:continue
            val parsed=uri(direct)?:continue
            val ext=fileExtension(direct)?:continue
            out.putIfAbsent(SearchRanking.canonicalKey(direct),
                OpenWebModelSearch.Candidate(title.take(180),
                    direct,ext.uppercase(Locale.ROOT),
                    parsed.host.orEmpty(),poster,page))
            if(out.size>=15)break
        }
        return out.values.toList()
    }
}
