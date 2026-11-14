package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.Locale

/** Exact gallery-page original selectors for hosts previously dropped into a
 * generic thumbnail scraper. Uses the integrated GalleryFlow queue/storage,
 * not another application or independent downloads list.
 */
internal object GallerySiteOriginalExtractors {
    private fun host(url:String):String = runCatching{
        URI(url).host.orEmpty().lowercase(Locale.ROOT).removePrefix("www.")
    }.getOrDefault("")

    fun isSpecial(url:String)=host(url) in setOf("pornpics.com","celeb.st")

    fun originals(doc:Document,selectedUrl:String):List<AlbumImageExtractor.Item>?{
        val source=host(selectedUrl)
        if(source !in setOf("pornpics.com","celeb.st"))return null
        // PornPics category/channel/search cards are not albums. Accept only
        // the selected /galleries/<slug> page; never download other results.
        if(source=="pornpics.com" &&
            !runCatching{URI(selectedUrl).path.orEmpty().matches(
                Regex("""(?i)/galleries/[^/]+/?"""))}.getOrDefault(false))
            return emptyList()
        // Only selected media containers; sidebars, creator recommendations,
        // avatars and site thumbnails are not actual original photographs.
        val selectors=when(source){
            "pornpics.com" ->
                "#gallery,.gallery-images,.gallery-content,.gallery-photos,"+
                ".photos-gallery,.gallery-pics,.gallery-wrapper,main,article"
            "celeb.st" ->
                "main .creator-gallery,main .post-gallery,main .image-viewer,"+
                "main .media-gallery,article .post-images,main .gallery-grid"
            else->return emptyList()
        }
        val containers=doc.select(selectors)
            .filter{it.closest("aside,.related,.recommended,.sidebar,nav,footer")==null}
        val seen=LinkedHashMap<String,AlbumImageExtractor.Item>()
        for(container in containers.take(12)){
            val isolated=Jsoup.parse(container.outerHtml(),selectedUrl)
            // The general <main> may include other galleries. Remove them
            // before resolving the publisher's original-image links.
            isolated.select("aside,.related,.recommended,.recommendations,"+
                ".you-may-like,.sidebar,nav,header,footer,.advertisement,.ads").remove()
            for(media in AlbumImageExtractor.originals(isolated,selectedUrl)){
                val link=runCatching{URI(media.url)}.getOrNull()?:continue
                if(link.scheme!="https" || link.host.isNullOrBlank() ||
                    link.userInfo!=null)continue
                if(!Regex("""(?i)\.(?:jpe?g|png|webp|avif|gif)$""")
                        .containsMatchIn(link.path.orEmpty()))continue
                val path=link.path.lowercase(Locale.ROOT)
                if(listOf("/thumb/","/thumbs/","thumbnail","-thumb",
                    "_small.","/preview/","/ads/","/avatars/").any{it in path})
                    continue
                seen.putIfAbsent(media.url,media)
            }
            // PornPics publishes original JPG links as anchors on the selected
            // gallery page; they are not always wrapped around a thumbnail.
            // Exact image URLs inside the scoped gallery are valid originals.
            if(source=="pornpics.com"){
                for(anchor in isolated.select("a[href]")){
                    val href=anchor.absUrl("href")
                    val uri=runCatching{URI(href)}.getOrNull()?:continue
                    if(uri.scheme!="https" || uri.host.isNullOrBlank() ||
                        uri.userInfo!=null ||
                        !Regex("""(?i)\\.(?:jpe?g|png|webp|avif)$""")
                            .containsMatchIn(uri.path.orEmpty()))continue
                    val p=uri.path.lowercase(Locale.ROOT)
                    if(listOf("/thumb/","/thumbs/","thumbnail","-thumb",
                        "_small.","/preview/","/ads/","/avatars/").any{it in p})
                        continue
                    seen.putIfAbsent(href,AlbumImageExtractor.Item(href,selectedUrl))
                }
            }
        }
        return seen.values.toList()
    }
}
