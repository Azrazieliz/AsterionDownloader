package com.asterion.videoflow

import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Provider-specific video-page recognition on already approved sources.
 * Recognizing a video PAGE is not proof of a downloadable original: VideoFlow
 * still resolves, validates and rejects previews before completing transfers.
 */
internal object VideoSiteProfiles {
    private data class Route(val domain:String,val video:Regex,
                             val listing:String="a[href]")
    private val routes=listOf(
        Route("pornhub.com",Regex("""(?i)^/view_video\.php\?[^#]*\bviewkey=[^&#]+""")),
        Route("xvideos.com",Regex("""(?i)^/video\d{2,14}(?:/|$)""")),
        Route("xnxx.com",Regex("""(?i)^/video-[a-z0-9]+(?:/|$)""")),
        Route("eporner.com",Regex("""(?i)^/hd-porn/[a-z0-9]+(?:/|$)""")),
        Route("hqpornerxxx.com",Regex("""(?i)^/(?:hdporn|video|videos)/[^/?#]+""")),
        Route("jav.guru",Regex("""(?i)^/[a-z0-9][a-z0-9_-]{3,170}/?$""")),
        Route("javideo.net",Regex("""(?i)^/(?:en/)?(?:video|watch|jav)/[^/?#]+""")),
        Route("roshy.tv",Regex("""(?i)^/(?:video|videos|watch)/[^/?#]+""")),
        Route("cums.net",Regex("""(?i)^/(?:video|watch|v)/[^/?#]+""")),
        Route("peekvids.com",Regex("""(?i)^/(?:v|video|videos)/[^/?#]+""")),
        Route("porntrex.com",Regex("""(?i)^/(?:video|videos)/[^/?#]+""")),
        Route("tnaflix.com",Regex("""(?i)^/(?:video|videos|hd-videos)/[^/?#]+""")),
        Route("watchporn.to",Regex("""(?i)^/(?:video|videos)/[^/?#]+""")),
        Route("pornhits.com",Regex("""(?i)^/(?:video|watch|videos)/[^/?#]+""")),
        Route("xxxclub.to",Regex("""(?i)^/(?:videos|video|watch)/[^/?#]+""")),
        Route("freepornvideos.xxx",Regex("""(?i)^/(?:video|videos|watch)/[^/?#]+""")),
        Route("omg.xxx",Regex("""(?i)^/(?:[a-z]{2}/)?(?:video|videos|watch)/[^/?#]+""")),
        Route("mp4hentai.com",Regex("""(?i)^/(?!category/)(?:video|watch|hentai)/[^/?#]+""")),
        Route("hentaibros.net",Regex("""(?i)^/(?:videos|video|watch)/[^/?#]+""")),
        Route("onlyhentaistuff.com",Regex("""(?i)^/(?:videos|video|watch)/[^/?#]+""")),
        Route("uncensoredhentai.xxx",Regex("""(?i)^/(?:videos|video|watch)/[^/?#]+""")),
        Route("3dhentaix.com",Regex("""(?i)^/(?:videos|video|watch)/[^/?#]+""")),
        Route("hentaihaven.xxx",Regex("""(?i)^/(?:video|videos|watch)/[^/?#]+""")),
        Route("rule34video.com",Regex("""(?i)^/(?:video|videos)/[^/?#]+""")),
        Route("camstreams.tv",Regex("""(?i)^/(?:video|videos|watch|clip)/[^/?#]+"""))
    ).associateBy{it.domain}
    private fun domain(raw:String):String=runCatching{
        var host=URI(raw).host.orEmpty().lowercase(Locale.ROOT)
        while(listOf("www.","m.","fr.","en.").any{host.startsWith(it)}){
            val prefix=listOf("www.","m.","fr.","en.")
                .first{host.startsWith(it)}
            host=host.removePrefix(prefix)
        }
        host
    }.getOrDefault("")
    /** A provider may redirect between its own mobile, language and www host
     * without changing source ownership. Never treat unrelated domains or
     * deceptive suffixes as the same provider.
     */
    fun sameProvider(a:String,b:String):Boolean{
        val first=domain(a)
        val second=domain(b)
        return first.isNotBlank() && first==second
    }
    fun isVideoPage(url:String):Boolean{
        val uri=runCatching{URI(url)}.getOrNull()?:return false
        if(uri.scheme !in setOf("https","http") || uri.userInfo!=null)return false
        val rule=routes[domain(url)]?:return false
        val path=uri.rawPath.orEmpty()+uri.rawQuery?.let{"?"+it}.orEmpty()
        if(Regex("""(?i)/(?:category|categories|tag|tags|models?|actors?|search|page)/""")
                .containsMatchIn(uri.path.orEmpty()))return false
        return rule.video.containsMatchIn(path)
    }
    fun next(doc:Document,source:String,current:String):String? {
        val host=domain(source)
        if(host !in routes)return null
        val now=runCatching{URI(current)}.getOrNull()?:return null
        val choices=doc.select("a[rel=next][href],.pagination a.next[href],"+
            "a.next[href],.pagenavi a.next[href],.pagination-next a[href],"+
            ".wp-pagenavi a.nextpostslink[href],.pagination a[aria-label=Next][href]")
        for(a in choices){
            val target=runCatching{URI(doc.location()).resolve(a.attr("href"))}
                .getOrNull()?:continue
            if(target.scheme !in setOf("https","http") ||
                target.host==null || domain(target.toString())!=host ||
                target.userInfo!=null || target.toString()==current)continue
            val sourcePath=URI(source).path.orEmpty().trimEnd('/')
            if(sourcePath.isNotBlank() &&
                !target.path.startsWith(sourcePath) &&
                !target.path.contains("/page/") &&
                !target.query.orEmpty().contains("page="))continue
            if(target.path==now.path && target.query==now.query)continue
            return target.toString()
        }
        return null
    }
    val coveredHosts: Set<String> get()=routes.keys
}
