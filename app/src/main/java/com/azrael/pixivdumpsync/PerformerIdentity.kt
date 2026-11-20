package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import android.content.Context
import com.azrael.galleryflow.SiteSession
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
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
    data class Proof(val displayName:String,val profileUrl:String,
                     val provider:String,val videoProfile:Boolean=true)
    // FullPorner publishes first-party /pornstar/<slug> profiles too.
    // Search discovery spans all approved providers separately; identity
    // verification requires an actual first-party person page.
    private val acceptedHosts=setOf(
        "pornhub.com","watchporn.to","fullporner.com","cums.net",
        // xChina publishes first-party model /model/id-<opaque>.html
        // pages. The ID cannot be derived from a name; live profile
        // search + visible heading verification are mandatory.
        "xchina.co")
    private val namePath=Regex("""^/(?:model|pornstar|models|pornstars|profiles)/([a-z0-9-]+)/?$""",
        RegexOption.IGNORE_CASE)

    fun slug(name:String):String=Normalizer.normalize(name,Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"),"-")
        .trim('-').take(90)

    private fun host(url:String):String=runCatching{
        URI(url).host.orEmpty().lowercase(Locale.ROOT)
            .removePrefix("www.").removePrefix("en.")
    }.getOrDefault("")

    fun isProfileUrl(name:String,address:String):Boolean {
        val uri=runCatching{URI(address)}.getOrNull()?:return false
        if(uri.scheme!="https" || uri.userInfo!=null ||
            host(address) !in acceptedHosts)return false
        val wanted=slug(name)
        if(wanted.isBlank())return false
        if(host(address)=="xchina.co"){
            // Real 2026 publisher URL:
            // https://en.xchina.co/model/id-5ff0cb8a616f1.html
            // It is a PERSON page, not evidence of video ownership.
            return Regex("""^/model/id-[a-f0-9]{8,24}\.html$""",
                RegexOption.IGNORE_CASE).matches(uri.path.orEmpty())
        }
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

    /**
     * Search multiple independent general web indexes for already-approved
     * first-party performer profiles. The old implementation guessed only four
     * URL slugs, so valid real models with provider-assigned profile paths
     * were categorically rejected. Search snippets NEVER establish identity:
     * every discovered URL still needs a live first-party profile with a
     * matching visible heading and canonical identity.
     */
    internal fun discoveredProfileUrls(name:String,html:String,base:String):List<String>{
        val doc=Jsoup.parse(html,base)
        val results=LinkedHashSet<String>()
        for(a in doc.select("a[href]").take(500)){
            val href=a.absUrl("href")
            val uri=runCatching{URI(href)}.getOrNull()?:continue
            val query=uri.rawQuery.orEmpty()
            val wrapped=query.split('&').firstOrNull{
                it.startsWith("uddg=") || it.startsWith("url=")
            }?.substringAfter('=')
            val candidate=when{
                wrapped!=null->runCatching{
                    URLDecoder.decode(wrapped,"UTF-8")
                }.getOrNull().orEmpty()
                else->href
            }
            if(isProfileUrl(name,candidate) ||
                PublicCreatorEvidence.trustedProfileCandidate(name,candidate))
                results+=candidate
            if(results.size>=30)break
        }
        return results.toList()
    }

    private fun searchProfileCandidates(name:String):List<String>{
        val query=URLEncoder.encode("\""+name.trim()+"\" model creator performer gravure","UTF-8")
        val engines=listOf(
            "https://www.bing.com/search?q="+query,
            "https://html.duckduckgo.com/html/?q="+query)
        val pool=Executors.newFixedThreadPool(2)
        try{
            val tasks=engines.map{address->pool.submit<List<String>>{
                runCatching {
                    val response=Jsoup.connect(address)
                        .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                        .followRedirects(true).ignoreHttpErrors(true)
                        .timeout(4_500).maxBodySize(1_500_000).execute()
                    if(response.statusCode() !in 200..299)emptyList()
                    else discoveredProfileUrls(name,response.body(),address)
                }.getOrDefault(emptyList())
            }}
            return tasks.flatMap{runCatching{
                it.get(5,TimeUnit.SECONDS)
            }.getOrDefault(emptyList())}.distinct().take(30)
        }finally{pool.shutdownNow()}
    }

    /** Only official profile patterns grounded in provider documentation are
     * candidates; each must pass HTTP, canonical-path and visible-name checks.
     */
    fun lookup(name:String,context:Context?=null):Proof? {
        val needle=name.trim()
        val handle=slug(needle)
        if(handle.length<2 || needle.length<2)return null
        // This independently published reference was verified from the
        // publisher itself, not inferred from a title-match video index.
        // Treat it as public CREATOR identity only (never video attribution).
        PublicCreatorEvidence.verifiedReference(needle)?.let{return Proof(
            needle,it,"Shueisha · public creator",videoProfile=false)}
        val urls=listOf(
            "https://www.pornhub.com/model/$handle",
            "https://www.pornhub.com/pornstar/$handle",
            "https://watchporn.to/models/$handle/",
            "https://fullporner.com/pornstar/$handle"
) + discoverCumsProfileUrls(needle,context) + searchProfileCandidates(needle)
        val pool=Executors.newFixedThreadPool(4)
        val completion=ExecutorCompletionService<Proof?>(pool)
        try{
            for(candidate in urls){
                completion.submit {
                    runCatching {
                        val response=Jsoup.connect(candidate)
                            .userAgent("Mozilla/5.0 (Linux; Android 17; Mobile)")
                            .followRedirects(false).ignoreHttpErrors(true)
                            .apply{SiteSession.cookie(candidate)?.let{header("Cookie",it)}}
                            .timeout(6_500).maxBodySize(1_800_000).execute()
                        val doc=if(response.statusCode() in 200..299)
                            response.parse()
                        else if(context!=null && SiteSession.bound(context,candidate))
                            Jsoup.parse(BrowserPageFetch.html(context,candidate),candidate)
                        else null
                        if(doc!=null && matchesEvidence(needle,candidate,doc))
                            Proof(needle,candidate,host(candidate),videoProfile=true)
                        else if(doc!=null && PublicCreatorEvidence.publishedPerson(
                                needle,candidate,doc))
                            Proof(needle,candidate,host(candidate),videoProfile=false)
                        else null
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
