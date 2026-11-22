package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

/**
 * Source-owned article scope for explicitly selected Cosplay sets.
 * Whole-page <img> discovery is unsafe: site recommendations and adjacent
 * galleries are frequently counted as if part of the tapped album.
 *
 * On an unrecognised layout, fail before downloading rather than save a
 * plausible-looking but mixed collection.
 */
internal object SelectedAlbumScope {
    private val supported=setOf(
        "cosplaytele.com","4khd.com","buondua.com","notfans.com")
    private val bodySelectors=listOf(
        "article .entry-content", "article .post-content",
        "article .post-body", "article .article-content",
        "article .single-content", "article .gallery-content",
        "article .wp-block-gallery", "main .entry-content",
        "main .post-content", "main .post-body",
        ".article-fulltext",".single-post-content",
        ".post-body",".article-content",".entry-content",
        ".gallery-images",".gallery-photos")
    private val excluded="aside,.related,.related-posts,.related-articles,"+
        ".related-posts-container,.recommended,.recommendations,"+
        ".you-may-like,.you-might-like,.other-posts,.other-gallery,"+
        ".other-galleries,.similar-posts,.suggestions,.widget,.sidebar,"+
        "nav,header,footer,.advertisement,.ads,.ad-container,"+
        ".author-box,.avatar,.post-navigation,.post-nav,.pagination"
    private val countPattern=Regex(
        """(?i)(?<![0-9])([0-9]{1,5})\s*(?:photos?|images?|pictures?|pics?)\b""")

    fun is4khdAlbum(url:String):Boolean=runCatching {
        val uri=URI(url)
        uri.scheme=="https" && uri.userInfo==null &&
            uri.host.orEmpty().lowercase(Locale.ROOT)
                .removePrefix("www.")=="4khd.com" &&
            uri.path.orEmpty().matches(
                Regex("""(?i)^/content/[0-9]{1,8}/[a-z0-9][a-z0-9_-]{2,180}\.html/?$"""))
    }.getOrDefault(false)

    /** The publisher calls these its full-HD originals; its paginated
     * website JPEGs are previews. Accept only first-party 4KHD bridge URLs
     * from the selected article, never ad links, related sets or broad pages.
     */
    fun officialOriginalArchives(doc:Document,selectedUrl:String):List<String>{
        require(is4khdAlbum(selectedUrl)){
            "Official full-quality 4KHD bridge needs an exact album URL"
        }
        val links=LinkedHashSet<String>()
        for(anchor in doc.select("main a[href],article a[href],"+
            ".entry-content a[href],.post-content a[href]").take(400)){
            if(anchor.closest("nav,header,footer,aside,.related,.recommended,"+
                    ".advertisement,.ads,.sidebar,.pagination")!=null)
                continue
            val uri=runCatching {
                URI(doc.baseUri()).resolve(anchor.attr("href"))
            }.getOrNull()?:continue
            val host=uri.host.orEmpty().lowercase(Locale.ROOT)
            if(uri.scheme!="https" || uri.userInfo!=null || uri.port!=-1 ||
                host!="m.4khd.com" ||
                !uri.path.orEmpty().matches(Regex("""^/[A-Za-z0-9_-]{4,60}/?$""")))
                continue
            val text=(anchor.text()+" "+anchor.parent()?.text().orEmpty())
                .lowercase(Locale.ROOT)
            if("terabox" !in text && "full hd" !in text &&
                "without watermark" !in text)
                continue
            links+=uri.toString().substringBefore('#')
        }
        return links.toList()
    }

    /** Two time-limited CDN signatures for the same host + original path
     * are one selected photo, including across album pagination. Keep the
     * original signed URL itself for the actual download; identity only
     * governs deduplication. Never merge files from different hosts/paths. */
    fun originalIdentity(url:String):String {
        val uri=runCatching{URI(url)}.getOrNull()?:return url
        if(uri.scheme !in setOf("http","https") || uri.host.isNullOrBlank() ||
            uri.userInfo!=null)return url
        return uri.scheme.lowercase(Locale.ROOT)+"://"+
            uri.host.lowercase(Locale.ROOT)+
            (if(uri.port==-1)"" else ":"+uri.port)+uri.path.orEmpty()
    }

    fun owns(url:String):Boolean=runCatching{
        val host=URI(url).host.orEmpty().lowercase(Locale.ROOT)
            .removePrefix("www.")
        host in supported
    }.getOrDefault(false)

    fun advertisedCount(vararg labels:String):Int? =
        labels.firstNotNullOfOrNull{label->
            countPattern.find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf{it in 1..10_000}
        }

    private fun articleBody(doc:Document):Element =
        bodySelectors.firstNotNullOfOrNull { selector ->
            doc.selectFirst(selector)?.takeIf { element ->
                element.select("img,picture source,a[href],video,"+
                    "video source,iframe[data-video],.video-player iframe").isNotEmpty()
            }
        }?:throw AdapterException(
            "Selected album lacks an identifiable publisher image body; "+
                "refusing to mix it with related galleries")

    /** The existing post-body isolation, also reused by the native
     * CosplayTele adapter. Never mine entire-page URLs or recommendation cards.
     */
    internal fun selectedContent(doc:Document,selectedUrl:String):Document {
        val body=articleBody(doc)
        val post=body.closest("article")
        val postId=post?.classNames()?.firstNotNullOfOrNull {
            Regex("^post-([0-9]+)$").matchEntire(it)?.groupValues?.get(1)
        } ?: post?.id()?.removePrefix("post-")?.takeIf { it.matches(Regex("[0-9]+")) }
        val wordpressGalleries=body.select(".gallery[class*=galleryid-]")
        val selectedGalleries=postId?.let { id ->
            wordpressGalleries.filter { it.hasClass("galleryid-$id") }
        }
        if(postId!=null && wordpressGalleries.isNotEmpty() && selectedGalleries.isNullOrEmpty())
            throw AdapterException("The selected post did not expose its own photo gallery")
        val content=selectedGalleries?.takeIf { it.isNotEmpty() }?.let{galleries->
            val photos=galleries.joinToString(""){it.outerHtml()}
            // WordPress galleryid-N contains photos ONLY. CosplayTele's
            // selected article can place its full video player or MP4 links
            // as siblings of that gallery. Dropping the siblings made an
            // otherwise complete photo set silently omit EVERY video.
            // Retain only direct media evidence inside the same post body.
            val videoExtras=if(URI(selectedUrl).host.orEmpty()
                    .lowercase(Locale.ROOT).removePrefix("www.")=="cosplaytele.com"){
                val nodes=body.select(
                    ".video-player,.wp-video,video,iframe[data-video],"+
                    "iframe[src],a[href$=.mp4],a[href*=.mp4?],"+
                    "a[href$=.webm],a[href*=.webm?]")
                nodes.filter{element->
                    val selectedFirstPartyFrame=if(element.tagName()=="iframe"){
                        val candidate=runCatching {
                            URI(doc.baseUri().ifBlank{selectedUrl})
                                .resolve(element.attr("src"))
                        }.getOrNull()
                        (element.hasAttr("data-video") &&
                            element.closest(".video-player")!=null) ||
                        (candidate!=null && candidate.scheme=="https" &&
                            candidate.userInfo==null && candidate.port==-1 &&
                            candidate.rawQuery.isNullOrBlank() &&
                            candidate.rawFragment==null &&
                            candidate.host.orEmpty().lowercase(Locale.ROOT)
                                .removePrefix("www.")=="cosplaytele.com" &&
                            Regex("""(?i)^/(?:embed|player|video)/[a-z0-9_-]{5,100}/?$""")
                                .matches(candidate.path.orEmpty()))
                    }else true
                    selectedFirstPartyFrame &&
                    element.closest(excluded)==null &&
                    element.closest(".gallery[class*=galleryid-]")==null &&
                    nodes.none{parent->parent!==element && element.parents().any{it===parent}}
                }.joinToString(""){it.outerHtml()}
            }else ""
            photos+videoExtras
        }?:body.outerHtml()
        val isolated=Jsoup.parse(content,doc.baseUri().ifBlank { selectedUrl })
        isolated.select(excluded).remove()
        // Strip links to other album pages, but keep only image/media links
        // and the current album's own lazy-original anchors.
        isolated.select("a[href]").forEach{anchor->
            val candidate=runCatching{URI(isolated.baseUri()).resolve(anchor.attr("href"))}
                .getOrNull()
            val path=candidate?.path.orEmpty().lowercase(Locale.ROOT)
            if(Regex("""\.(?:mp4|m4v|webm|mov)$""").containsMatchIn(path)) {
                // Selected CosplayTele videos were previously removed here
                // because plain media anchors have no img/picture child.
                // Retain only direct video-file links, never related posts.
            }else if(Regex("""\.(?:jpe?g|png|gif|webp|avif)$""").containsMatchIn(path)) {
                // Keep one representation when an explicit original wraps a
                // responsive picture, whose source URLs are alternate encodings.
                if(anchor.select("img,picture").isNotEmpty()){
                    anchor.attr("download","")
                    anchor.select("picture,img").remove()
                }
            }else if(anchor.select("img,picture").isEmpty()) anchor.remove()
            else if(candidate!=null && samePublisher(selectedUrl,candidate) &&
                candidate.path.trimEnd('/')!=URI(selectedUrl).path.trimEnd('/'))
                anchor.remove()
        }
        return isolated
    }

    fun originals(doc:Document,selectedUrl:String):List<AlbumImageExtractor.Item> {
        val isolated=selectedContent(doc,selectedUrl)
        val originals=AlbumImageExtractor.originals(isolated,selectedUrl)
        val seen=LinkedHashMap<String,AlbumImageExtractor.Item>()
        for(item in originals){
            val uri=runCatching{URI(item.url)}.getOrNull()?:continue
            if(uri.scheme !in setOf("http","https") || uri.userInfo!=null)continue
            // Different CDN query signatures may name the same original.
            seen.putIfAbsent(originalIdentity(item.url),item)
        }
        return seen.values.toList()
    }

    private fun samePublisher(url:String,uri:URI):Boolean =
        URI(url).host.orEmpty().removePrefix("www.").equals(
            uri.host.orEmpty().removePrefix("www."),ignoreCase=true)

    /** A publisher's '37 photos' is a count of photos, not videos or
     * video-only archives. Preserve all selected media but compare image
     * members only; full original archives have their own receipt counts.
     */
    internal fun verifySelectedPhotos(expected:Int?,media:List<MediaRef>):Boolean {
        if(expected==null || media.any{it.expectedArchivePhotos!=null})return false
        verifyAdvertisedCount(expected,media.count{it.kind==MediaKind.IMAGE})
        return true
    }

    fun verifyAdvertisedCount(expected:Int?,actual:Int){
        if(expected!=null && actual!=expected)
            throw AdapterException(
                "Album declares $expected photos, but publisher-scoped extraction "+
                "found $actual. No mixed or incomplete album will be saved.")
    }
}
