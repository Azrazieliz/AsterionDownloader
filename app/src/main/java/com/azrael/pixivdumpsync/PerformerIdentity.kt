package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.content.Context
import com.azrael.galleryflow.SiteSession
import java.net.URI
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.TimeUnit

/** Evidence-based performer following. A search-title match is NOT a person
 * identity. Only an actual accessible first-party performer profile with a
 * matching prominent name can establish a follow.
 */
internal object PerformerIdentity {
    data class Proof(val displayName:String,val profileUrl:String,val provider:String)
    // FullPorner publishes first-party /pornstar/<slug> profiles too.
    // Search discovery spans all approved providers separately; identity
    // verification requires an actual first-party person page.
    private val acceptedHosts=setOf("pornhub.com","watchporn.to","fullporner.com","cums.net")
    private val namePath=Regex("""^/(?:model|pornstar|models|pornstars|profiles)/([a-z0-9-]+)/?$""",
        RegexOption.IGNORE_CASE)

    fun slug(name:String):String=Normalizer.normalize(name,Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"-")
        .trim('-').take(90)

    private fun host(url:String):String=runCatching{
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    fun isProfileUrl(name:String,address:String):Boolean {
        val uri=runCatching{URI(address)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null ||
            host(address) !in acceptedHosts)return false
        val wanted=slug(name)
        if(wanted.isBlank())return false
        if(host(address)=="cums.net"){
            // First-party CUMS profiles use /model/<name>-<site-ID>/,
            // not a guessable /model/<name>/ address.
            val path=uri.path.orEmpty().trim('/')
            val matched=Regex("""^model/([a-z0-9-]+)-([a-z0-9]{5,12})$""",
                RegexOption.IGNORE_CASE).matchEntire(path)?:return false
            return matched.groupValues[1].lowercase()==wanted
        }
        val match=namePath.matchEntire(uri.path.orEmpty())?:return false
        return wanted==match.groupValues[1].lowercase()
    }

    fun matchesEvidence(name:String,address:String,document:Document):Boolean {
        if(!isProfileUrl(name,address))return false
        val prominent=document.select(
            "h1,.model-name,.pornstar-name,.performer-name,[itemprop=name]").eachText()
            .map{slug(it)}
        val canonical=document.selectFirst("link[rel=canonical]")
            ?.absUrl("href").orEmpty()
        if(canonical.isNotBlank() && !isProfileUrl(name,canonical))return false
        val heading=prominent.any{it==slug(name) ||
            (host(address)=="cums.net" &&
                it==slug(name+" Porn and Nude Videos"))}
        val typedPerson=document.select("script[type=application/ld+json]")
            .any{node->
                val data=node.data().ifBlank{node.html()}
                data.contains(Regex("\"@type\"\\s*:\\s*\"Person\"",RegexOption.IGNORE_CASE)) &&
                    data.contains(Regex("\"name\"\\s*:\\s*\""+
                        Regex.escape(name.trim())+"\"",RegexOption.IGNORE_CASE))
            }
        return heading || typedPerson
    }

    /**
     * CUMS publishes real model links with opaque IDs. Discover exact
     * first-party links instead of guessing an ID or trusting a video title.
     */
    private fun discoverCumsProfileUrls(name:String,context:Context?):List<String>{
        val directory="https://cums.net/model/"
        val html=runCatching {
            val response=Jsoup.connect(directory)
                .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                .followRedirects(false).ignoreHttpErrors(true)
                .apply{SiteSession.cookie(directory)?.let{header("Cookie",it)}}
                .timeout(7_000).maxBodySize(2_500_000).execute()
            if(response.statusCode() in 200..299)response.body()
            else if(context!=null && SiteSession.bound(context,directory))
                BrowserPageFetch.html(context,directory)
            else return emptyList()
        }.getOrNull()?:return emptyList()
        val doc=Jsoup.parse(html,directory)
        return doc.select("a[href*='/model/']").mapNotNull{a->
            val url=a.absUrl("href")
            url.takeIf{isProfileUrl(name,it)}
        }.distinct()
    }

    /** Only official profile patterns grounded in provider documentation are
     * candidates; each must pass HTTP, canonical-path and visible-name checks.
     */
    fun lookup(name:String,context:Context?=null):Proof? {
        val needle=name.trim()
        val handle=slug(needle)
        if(handle.length<2 || needle.length<2)return null
        val urls=listOf(
            "https://www.pornhub.com/model/$handle",
            "https://www.pornhub.com/pornstar/$handle",
            "https://watchporn.to/models/$handle/",
            "https://fullporner.com/pornstar/$handle"
        ) + discoverCumsProfileUrls(needle,context)
        val pool=Executors.newFixedThreadPool(4)
        val completion=ExecutorCompletionService<Proof?>(pool)
        try{
            for(candidate in urls){
                completion.submit {
                    runCatching {
                        val response=Jsoup.connect(candidate)
                            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                            .followRedirects(false).ignoreHttpErrors(true)
                            .timeout(6_500).maxBodySize(1_800_000).execute()
                        if(response.statusCode() !in 200..299)null
                        else {
                            val doc=response.parse()
                            if(matchesEvidence(needle,candidate,doc))
                                Proof(needle,candidate,host(candidate))
                            else null
                        }
                    }.getOrNull()
                }
            }
            val deadline=System.nanoTime()+12_000_000_000L
            repeat(urls.size){
                val remaining=deadline-System.nanoTime()
                if(remaining<=0L)return null
                val result=completion.poll(remaining,TimeUnit.NANOSECONDS)
                    ?:return null
                result.get()?.let{return it}
            }
            return null
        }finally{
            pool.shutdownNow()
        }
    }
}
