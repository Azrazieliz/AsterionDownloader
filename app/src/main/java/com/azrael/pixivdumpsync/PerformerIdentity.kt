package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
    private val acceptedHosts=setOf("pornhub.com","xvideos.com","xnxx.com","watchporn.to")
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
        val match=namePath.matchEntire(uri.path.orEmpty())?:return false
        return slug(name).isNotBlank() && slug(name)==match.groupValues[1].lowercase()
    }

    fun matchesEvidence(name:String,address:String,document:Document):Boolean {
        if(!isProfileUrl(name,address))return false
        val prominent=document.select(
            "h1,.model-name,.pornstar-name,[itemprop=name]").eachText()
            .map{slug(it)}
        val canonical=document.selectFirst("link[rel=canonical]")
            ?.absUrl("href").orEmpty()
        if(canonical.isNotBlank() && !isProfileUrl(name,canonical))return false
        val heading=prominent.any{it==slug(name)}
        val typedPerson=document.select("script[type=application/ld+json]")
            .any{node->
                val data=node.data().ifBlank{node.html()}
                data.contains(Regex("\"@type\"\\s*:\\s*\"Person\"",RegexOption.IGNORE_CASE)) &&
                    data.contains(Regex("\"name\"\\s*:\\s*\""+
                        Regex.escape(name.trim())+"\"",RegexOption.IGNORE_CASE))
            }
        return heading || typedPerson
    }

    /** Only official profile patterns grounded in provider documentation are
     * candidates; each must pass HTTP, canonical-path and visible-name checks.
     */
    fun lookup(name:String):Proof? {
        val needle=name.trim()
        val handle=slug(needle)
        if(handle.length<2 || needle.length<2)return null
        val urls=listOf(
            "https://www.pornhub.com/model/$handle",
            "https://www.pornhub.com/pornstar/$handle",
            "https://www.xvideos.com/pornstars/$handle",
            "https://www.xvideos.com/profiles/$handle",
            "https://www.xnxx.com/pornstars/$handle",
            "https://watchporn.to/models/$handle/"
        )
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
