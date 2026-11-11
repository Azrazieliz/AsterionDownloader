package com.azrael.pixivdumpsync

import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Post/product index adapters for the creator websites explicitly enrolled
 * in Asterion. Extract only real same-provider post URLs; obtaining a post URL
 * never implies entitlement to its attachments. The original-file resolver
 * and connected account handle accessible attachments separately.
 */
internal object CreatorSiteBackfill {
    private data class Route(val host:String,val area:String,
                             val post:Regex,val kind:String,
                             val selector:String="article a[href],main a[href],"+
                                 ".post-list a[href],.post-card a[href],"+
                                 ".posts a[href],.creator-post a[href],"+
                                 ".entry a[href],.product-card a[href]")
    private val routes=listOf(
        Route("patreon.com","CREATORS",
            Regex("""^/posts/[A-Za-z0-9_-]*[0-9]{4,}/?$"""),"creators"),
        Route("fanbox.cc","CREATORS",
            Regex("""^/posts/[0-9]{1,20}/?$"""),"creators"),
        Route("fantia.jp","CREATORS",
            Regex("""^/(?:posts|products)/[0-9]{1,20}/?$"""),"creators"),
        Route("onlyfans.com","CREATORS",
            Regex("""^/(?:[A-Za-z0-9._-]+/posts/[0-9]{1,20}|[0-9]{1,20}/[A-Za-z0-9._-]+)/?$"""),
            "creators"),
        Route("fansly.com","CREATORS",
            Regex("""^/(?:post/[0-9]{1,20}|[A-Za-z0-9._-]+/posts/[0-9]{1,20})/?$"""),
            "creators"),
        Route("gumroad.com","ASSETS",
            Regex("""^/(?:l|products)/[A-Za-z0-9_-]{3,200}/?$"""),"assets"),
        Route("koikatsucards.com","ASSETS",
            Regex("""^/(?:card|cards|post)/[A-Za-z0-9_-]{1,100}/?$"""),"assets"),
        Route("f95zone.to","GAMES",
            Regex("""^/threads/[A-Za-z0-9._-]*[0-9]{3,}/?$"""),"games"),
        Route("shionlib.com","BOOKS",
            Regex("""^/(?:book|books|novel)/[A-Za-z0-9_-]+/?$"""),"books"),
        Route("annas-archive.org","BOOKS",
            Regex("""^/md5/[a-fA-F0-9]{32}/?$"""),"books")
    ).associateBy{it.host}
    private fun normalizedHost(raw:String):String {
        var h=runCatching{URI(raw).host.orEmpty().lowercase(Locale.ROOT)}
            .getOrDefault("")
        for(prefix in listOf("www.","m.")){
            if(h.startsWith(prefix))h=h.removePrefix(prefix)
        }
        if(h.endsWith(".fanbox.cc"))h="fanbox.cc"
        return h
    }
    val supportedHosts:Set<String> get()=routes.keys
    fun entries(doc:Document,source:String,area:String,now:Long):
            List<IndexedItem>?{
        val host=normalizedHost(source)
        val route=routes[host]?.takeIf{it.area==area}?:return null
        val result=LinkedHashMap<String,IndexedItem>()
        for(link in doc.select(route.selector).take(15000)){
            val raw=link.attr("href")
            if(raw.isBlank())continue
            val url=runCatching{URI(doc.location()).resolve(raw)}
                .getOrNull()?:continue
            if(url.scheme!="https" || url.userInfo!=null ||
                !SourceIndexParser.safe(url.toString()) ||
                normalizedHost(url.toString())!=host ||
                !SourceIndexParser.sameSite(source,url.toString()) ||
                !route.post.matches(url.path.orEmpty()))continue
            val title=link.attr("title").ifBlank{link.text()}
                .ifBlank{link.selectFirst("img")?.attr("alt").orEmpty()}
                .ifBlank{link.closest("article,.post,.product")
                    ?.selectFirst("h1,h2,h3")?.text().orEmpty()}
                .trim().take(180)
            if(title.length<3)continue
            val image=link.selectFirst("img")?.let{img->
                listOf("data-original","data-src","src")
                    .map{img.absUrl(it)}.firstOrNull{SourceIndexParser.safe(it)}
            }
            val canonical=url.toString().substringBefore('#').trimEnd('/')+"/"
            result.putIfAbsent(canonical,IndexedItem(canonical,title,image,
                source,area,route.kind,now,false))
            require(result.size<3000){
                "Creator post listing exceeds one-page capacity; cursor not advanced"
            }
        }
        require(doc.select(route.selector).size<=15000){
            "Creator site has over 15000 listing links; use a specific API adapter"
        }
        return result.values.toList()
    }
}
