package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Offline source fixture; an official bridge is not a downloaded archive. */
class FourKhdRenderedArchiveTest {
    @Test fun onlySelectedArticleRenderedPublisherBridgeCanBecomeArchive(){
        val selected="https://www.4khd.com/content/1234/selected-album.html"
        val publisherBridge="https://m.4khd.com/SelectedBridge42"
        val page="<main><article><h1>Selected album</h1>"+
            "<img src='https://www.4khd.com/media/preview.jpg'>"+
            "</article></main>"
        val rendered="<main><article><h1>Selected album</h1>"+
            "<a href='$publisherBridge'>Full HD TeraBox original without watermark</a>"+
            "<aside class='related'><a href='https://m.4khd.com/WrongAlbumArchive'>"+
            "Other gallery TeraBox</a></aside></article></main>"
        val http=object:HttpClient(){
            override fun document(url:String,referer:String?):org.jsoup.nodes.Document{
                require(url==selected)
                return Jsoup.parse(page,selected)
            }
            override fun selected4KhdArticleHtml(selected:String):String {
                require(selected=="https://www.4khd.com/content/1234/selected-album.html")
                return rendered
            }
        }
        val gallery=GalleryRef(SourceId.FOUR_K_HD,"1234:selected-album",
            "gallery:1234","https://www.4khd.com/content/1234/selected-album.html",
            "Selected album")
        val (_,media)=GenericSiteAdapter(http).fetchGallery(gallery)
        assertEquals(1,media.size)
        assertEquals(MediaKind.ARCHIVE,media.single().kind)
        assertEquals(publisherBridge,media.single().url)
        assertFalse(media.single().url.contains("preview"))
    }
}
