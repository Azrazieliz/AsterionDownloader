package com.asterion.videoflow

import android.content.Context
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/**
 * MP4Hentai posts publish named Mirror/Upfiles hoster links rather than an
 * HTML5 video. Follow only the selected post's declared links; never treat
 * the hoster HTML, adverts, recommendation MP4s or an arbitrary redirect as
 * the selected original.
 */
internal object Mp4HentaiPublishedHost {
    private val post=Regex("""^/20[0-9]{2}/(?:0[1-9]|1[0-2])/[^/?#]{3,300}/?$""")
    private val hosters=setOf("mirrored.to","upfiles.com","upfilesgo.com")

    private fun uri(address:String):URI?=runCatching{URI(address)}.getOrNull()
    private fun hostname(address:String):String=uri(address)?.host.orEmpty()
        .lowercase(Locale.ROOT).removePrefix("www.")
    private fun safe(address:String):Boolean {
        val u=uri(address)?:return false
        return u.scheme=="https" && u.userInfo==null && u.port==-1 &&
            WebDiscovery.isUrl(address)
    }
    private fun isHosterPage(url:String):Boolean {
        if(!safe(url) || hostname(url) !in hosters)return false
        val path=uri(url)?.path.orEmpty()
        return when(hostname(url)){
            "mirrored.to" -> path.startsWith("/files/")
            "upfiles.com","upfilesgo.com" ->
                Regex("""^/[A-Za-z0-9_-]{3,100}(?:/.*)?$""").matches(path)
            else->false
        }
    }
    fun isPublishedHost(selected:String,link:String):Boolean =
        hostname(selected)=="mp4hentai.com" &&
            post.matches(uri(selected)?.path.orEmpty()) && isHosterPage(link)

    fun selectedLinks(doc:Document,selected:String):List<String>{
        if(hostname(selected)!="mp4hentai.com" ||
            !post.matches(uri(selected)?.path.orEmpty()))return emptyList()
        val found=LinkedHashSet<String>()
        for(anchor in doc.select("article .entry-content a[href],"+
            "article .post-content a[href],.entry-content a[href],"+
            ".post-content a[href]").take(150)){
            if(anchor.closest(".related,.recommended,.sidebar,.ads,"+
                    ".advertisement,.post-navigation")!=null)continue
            val label=(anchor.text()+" "+anchor.attr("title"))
                .trim().lowercase(Locale.ROOT)
            if(!Regex("""(?i)^(?:mirror|upfiles)(?:\s+download)?\s*$""")
                    .matches(label))continue
            val candidate=runCatching{
                URI(selected).resolve(anchor.attr("href")).toString()
                    .substringBefore('#')
            }.getOrNull()?:continue
            if(isPublishedHost(selected,candidate))found+=candidate
        }
        return found.toList()
    }

    private fun direct(url:String):Boolean =
        safe(url) && NativeMediaDownloader.isDirectMediaUrl(url) &&
            !FullLengthVideoPolicy.isExplicitPreview("",url)

    /**
     * Bounded public redirect/HTML link resolution. Does not automate login,
     * verification or captchas. A concrete MP4 link is handed to the normal
     * native transfer, where MIME and complete byte ranges are validated.
     */
    fun resolve(context:Context,selected:String,landing:String):String {
        require(isPublishedHost(selected,landing)){"Untrusted MP4Hentai file host"}
        var current=landing
        var referer=selected
        val visited=HashSet<String>()
        repeat(5){
            check(visited.add(current)){"MP4Hentai file-host redirect loop"}
            val response=Jsoup.connect(current)
                .userAgent(NativeMediaDownloader.USER_AGENT)
                .referrer(referer)
                .ignoreHttpErrors(true).ignoreContentType(true)
                .followRedirects(false).timeout(14_000)
                .maxBodySize(1_500_000)
                .apply{NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}}
                .execute()
            val status=response.statusCode()
            if(status in 300..399){
                val next=response.header("Location")?.takeIf{it.isNotBlank()}
                    ?.let{URI(current).resolve(it).toString()}
                    ?:error("MP4Hentai host redirect missing destination")
                if(direct(next))return next
                check(isHosterPage(next)){
                    "MP4Hentai host redirected to an unverified download page"
                }
                referer=current
                current=next
            }else{
                check(status in 200..299){
                    "MP4Hentai selected file host HTTP $status"
                }
                check(response.contentType().orEmpty()
                    .lowercase(Locale.ROOT).contains("html")){
                    "MP4Hentai host did not expose a verified HTML download page"
                }
                val doc=Jsoup.parse(response.body(),current)
                val candidates=LinkedHashSet<String>()
                var nextLanding:String?=null
                for(anchor in doc.select("a[href]").take(1000)){
                    if(anchor.closest("aside,.related,.recommended,.ads,"+
                            ".advertisement,.sidebar")!=null)continue
                    val label=(anchor.text()+" "+anchor.attr("title"))
                        .trim().lowercase(Locale.ROOT)
                    val download=anchor.hasAttr("download") ||
                        Regex("""(?i)(?:download|direct link|get file)""")
                            .containsMatchIn(label)
                    if(!download)continue
                    val link=runCatching{
                        URI(current).resolve(anchor.attr("href")).toString()
                    }.getOrNull()?:continue
                    if(direct(link))candidates+=link
                    else if(nextLanding==null && isHosterPage(link) &&
                        link!=current)nextLanding=link
                }
                if(candidates.size==1)return candidates.single()
                check(candidates.isEmpty()){
                    "MP4Hentai host exposed multiple unverified MP4 downloads"
                }
                if(nextLanding==null)error(
                    "MP4Hentai published host requires a real direct file link "+
                    "or an authorized browser session")
                referer=current
                current=nextLanding
            }
        }
        error("MP4Hentai host did not expose a verified download in five steps")
    }
}
