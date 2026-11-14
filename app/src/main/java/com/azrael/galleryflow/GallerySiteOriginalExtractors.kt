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
        // Only selected media containers; sidebars, creator recommendations,
        // avatars and site thumbnails are not actual original photographs.
        val selectors=when(source){
            "pornpics.com" ->
                "#gallery,.gallery-images,.gallery-content,.gallery-photos,"+
                ".photos-gallery,main .gallery"
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
        }
        return seen.values.toList()
    }
}
