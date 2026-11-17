package com.asterion.videoflow

import android.content.Context
import com.azrael.galleryflow.SiteSession
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
            val candidate=publishedHref(selected,anchor.attr("href"))?:continue
            if(isPublishedHost(selected,candidate))found+=candidate
        }
        return found.toList()
    }

    /** The publisher uses literal [scene] prefixes in real mirrored.to
     * hrefs. java.net.URI rejects raw square brackets and previously dropped
     * that official Mirror download link without reporting any error.
     * Percent-encode only illegal path characters; never relax host checks.
     */
    internal fun publishedHref(base:String,raw:String):String?=runCatching{
        URI(base).resolve(raw.trim().replace("&amp;","&")
            .replace(" ","%20").replace("[","%5B").replace("]","%5D"))
            .toString().substringBefore('#').takeIf(::safe)
    }.getOrNull()

    internal fun requiresInteractiveVerification(doc:Document):Boolean =
        doc.select("form#file-captcha,form[action*=captcha],"+
            "input[name*=captcha],.cf-turnstile,[data-sitekey],"+
            ".g-recaptcha").isNotEmpty() ||
            doc.title().contains("Just a moment",ignoreCase=true)

    internal data class FreeDownloadForm(val action:String,val csrf:String)

    /** The selected public UpFiles page offers one ordinary FREE form.
     * It is not a direct file URL, nor an authorization to solve CAPTCHA,
     * skip paid queues or guess the subsequent CDN address.
     */
    internal fun publicFreeDownloadForm(doc:Document,current:String):FreeDownloadForm? {
        if(hostname(current)!="upfilesgo.com" ||
            !isHosterPage(current) || requiresInteractiveVerification(doc))
            return null
        val forms=doc.select("form#free-download-form")
        if(forms.size!=1)return null
        val form=forms.single()
        if(!form.attr("method").equals("post",true))return null
        val destination=publishedHref(current,form.attr("action"))?:return null
        if(!isHosterPage(destination) ||
            hostname(destination)!=hostname(current) ||
            uri(destination)?.path!=uri(current)?.path ||
            !uri(destination)?.rawQuery.isNullOrBlank())return null
        val fields=form.select("input[name]")
            .associate{it.attr("name") to it.attr("value")}
        if(fields.keys!=setOf("_token","download_type") ||
            !fields["download_type"].equals("free",true))return null
        val csrf=fields["_token"].orEmpty()
        if(!csrf.matches(Regex("[A-Za-z0-9_-]{16,300}")))return null
        return FreeDownloadForm(destination,csrf)
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
                .apply{
                    // Reuse only the owner's already-approved session for
                    // THIS exact file host. Never forward publisher cookies
                    // through a cross-host redirect or into advertising.
                    SiteSession.cookie(context,current)?.takeIf{it.isNotBlank()}
                        ?.let{header("Cookie",it)}
                    NetworkRoute.javaProxy(context,selected)?.let{proxy(it)}
                }
                .execute()
            val status=response.statusCode()
            if(status in 300..399){
                val next=response.header("Location")?.takeIf{it.isNotBlank()}
                    ?.let{publishedHref(current,it)}
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
                // This is the exact selected UpFiles public free-download
                // action, obtained with the server's own CSRF token. It is
                // not a CAPTCHA, paid tier, unrelated button or guessed MP4.
                // Only same-hoster session cookies are sent to this form.
                val free=publicFreeDownloadForm(doc,current)
                var publicReply=if(free!=null) {
                    val posted=Jsoup.connect(free.action)
                        .userAgent(NativeMediaDownloader.USER_AGENT)
                        .referrer(current)
                        .header("Origin",URI(current).let{
                            it.scheme+"://"+it.host
                        })
                        .cookies(response.cookies())
                        .data("_token",free.csrf)
                        .data("download_type","free")
                        .method(org.jsoup.Connection.Method.POST)
                        .ignoreHttpErrors(true).ignoreContentType(true)
                        .followRedirects(false).timeout(14_000)
                        .maxBodySize(1_500_000)
                        .apply {
                            SiteSession.cookie(context,current)
                                ?.takeIf{it.isNotBlank()}?.let{header("Cookie",it)}
                            NetworkRoute.javaProxy(context,selected)
                                ?.let{proxy(it)}
                        }.execute()
                    if(posted.statusCode() in 300..399){
                        val forwarded=posted.header("Location")
                            ?.let{publishedHref(current,it)}
                            ?:error("UpFiles free-download response omitted redirect")
                        if(direct(forwarded))return forwarded
                        check(isHosterPage(forwarded)){
                            "UpFiles free-download redirected outside its verified hosters"
                        }
                        referer=current
                        current=forwarded
                        return@repeat
                    }
                    check(posted.statusCode() in 200..299 &&
                        posted.contentType().orEmpty().contains("html",true)){
                        "UpFiles public free download did not expose a verified file link"
                    }
                    Jsoup.parse(posted.body(),current)
                } else doc
                // The provider may require a genuinely interactive CAPTCHA
                // AFTER its public free form. Surface that requirement;
                // neither guess its answer nor mislabel HTML as a download.
                if(requiresInteractiveVerification(publicReply) &&
                    SiteSession.bound(context,current)) {
                    // Reuse the ordinary user-completed hoster verification
                    // from Connect Site. Do not synthesize a CAPTCHA token
                    // or interpret the verification page as a video.
                    val authorized=runCatching {
                        com.azrael.pixivdumpsync.BrowserPageFetch.html(
                            context,current,playerReferer=referer)
                    }.getOrNull()
                    if(!authorized.isNullOrBlank()){
                        val rendered=Jsoup.parse(authorized,current)
                        if(!requiresInteractiveVerification(rendered))
                            publicReply=rendered
                    }
                }
                if(requiresInteractiveVerification(publicReply)) {
                    error("MP4Hentai selected file host requires CAPTCHA "+
                        "verification in an authorized browser session")
                }
                val candidates=LinkedHashSet<String>()
                var nextLanding:String?=null
                for(anchor in publicReply.select("a[href]").take(1000)){
                    if(anchor.closest("aside,.related,.recommended,.ads,"+
                            ".advertisement,.sidebar")!=null)continue
                    val label=(anchor.text()+" "+anchor.attr("title"))
                        .trim().lowercase(Locale.ROOT)
                    val download=anchor.hasAttr("download") ||
                        Regex("""(?i)(?:download|direct link|get file)""")
                            .containsMatchIn(label)
                    if(!download)continue
                    val link=publishedHref(current,anchor.attr("href"))
                        ?:continue
                    if(direct(link))candidates+=link
                    else if(nextLanding==null && isHosterPage(link) &&
                        link!=current)nextLanding=link
                }
                if(candidates.size==1)return candidates.single()
                check(candidates.isEmpty()){
                    "MP4Hentai host exposed multiple unverified MP4 downloads"
                }
                // Some published hosting links are inserted only after the
                // hoster's own public JavaScript finishes. Read that exact
                // page in the existing browser facility, without scraping
                // unrelated video pages or forwarding publisher cookies.
                if(nextLanding==null &&
                    com.azrael.pixivdumpsync.BrowserPageFetch.publicEligible(current)){
                    val rendered=runCatching{
                        com.azrael.pixivdumpsync.BrowserPageFetch
                            .html(context,current,playerReferer=referer)
                    }.getOrNull()
                    if(!rendered.isNullOrBlank()){
                        val browser=Jsoup.parse(rendered,current)
                        val browserDirect=LinkedHashSet<String>()
                        for(anchor in browser.select("a[href]").take(1000)){
                            val label=(anchor.text()+" "+anchor.attr("title"))
                                .trim().lowercase(Locale.ROOT)
                            if(!anchor.hasAttr("download") &&
                                !Regex("""(?i)(?:download|direct link|get file)""")
                                    .containsMatchIn(label))continue
                            val link=publishedHref(current,anchor.attr("href"))
                                ?:continue
                            if(direct(link))browserDirect+=link
                            else if(nextLanding==null && isHosterPage(link) &&
                                link!=current)nextLanding=link
                        }
                        if(browserDirect.size==1)return browserDirect.single()
                        check(browserDirect.isEmpty()){
                            "MP4Hentai browser exposed ambiguous media files"
                        }
                    }
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
