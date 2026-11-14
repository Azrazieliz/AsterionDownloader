package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

    fun originals(doc:Document,selectedUrl:String):List<AlbumImageExtractor.Item> {
        val body=bodySelectors.firstNotNullOfOrNull { selector ->
            doc.selectFirst(selector)?.takeIf { element ->
                element.select("img,picture source,a[href]").isNotEmpty()
            }
        }?:throw AdapterException(
            "Selected album lacks an identifiable publisher image body; "+
                "refusing to mix it with related galleries")
        val isolated=Jsoup.parse(body.outerHtml(),selectedUrl)
        isolated.select(excluded).remove()
        // Strip links to other album pages, but keep only image/media links
        // and the current album's own lazy-original anchors.
        isolated.select("a[href]").forEach{anchor->
            val candidate=runCatching{URI(selectedUrl).resolve(anchor.attr("href"))}
                .getOrNull()
            val path=candidate?.path.orEmpty().lowercase(Locale.ROOT)
            if(!Regex("""\.(?:jpe?g|png|gif|webp|avif)$""")
                    .containsMatchIn(path) &&
                anchor.select("img,picture").isEmpty())
                anchor.remove()
        }
        val originals=AlbumImageExtractor.originals(isolated,selectedUrl)
        val seen=LinkedHashMap<String,AlbumImageExtractor.Item>()
        for(item in originals){
            val uri=runCatching{URI(item.url)}.getOrNull()?:continue
            if(uri.scheme !in setOf("http","https") || uri.userInfo!=null)continue
            // Different CDN query signatures may name the same original.
            val identity=uri.scheme+"://"+uri.host.orEmpty().lowercase(Locale.ROOT)+
                uri.path.orEmpty()
            seen.putIfAbsent(identity,item)
        }
        return seen.values.toList()
    }

    fun verifyAdvertisedCount(expected:Int?,actual:Int){
        if(expected!=null && actual!=expected)
            throw AdapterException(
                "Album declares $expected photos, but publisher-scoped extraction "+
                "found $actual. No mixed or incomplete album will be saved.")
    }
}
