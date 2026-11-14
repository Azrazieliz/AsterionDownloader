package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class FourKhdOriginalArchiveTest {
    @Test fun selectingAnAlbumQueuesRealFullHdArchiveNotPreviewJpegs(){
        val selected="https://www.4khd.com/content/14/yeha-yeha-pure2.html"
        val fake=object:HttpClient(){
            override fun document(url:String,referer:String?):Document{
                assertEquals(selected,url)
                return Jsoup.parse("""
                    <html><title>Yeha Pure#2[1.62GB-212photos]</title><body>
                    <main><article class="entry-content">
                       <h1>Yeha Pure#2[1.62GB-212photos]</h1>
                       <a href="https://m.4khd.com/45ycnl">
                          Full HD without watermark download: TeraBox</a>
                       <div class="gallery">
                          <img src="https://cdn.4khd.com/preview.jpg">
                       </div>
                       <div class="related"><img
                          src="https://cdn.4khd.com/other-set.jpg"></div>
                    </article></main>
                    </body></html>
                """.trimIndent(),url)
            }
        }
        val album=GalleryRef(SourceId.GENERIC,"current-gallery",
            "site:4khd.com",selected,"Yeha Pure#2[1.62GB-212photos]")
        val (meta,media)=GenericSiteAdapter(fake).fetchGallery(album)
        assertEquals(selected,meta.canonicalUrl)
        assertEquals(1,media.size)
        assertEquals(MediaKind.ARCHIVE,media.single().kind)
        assertEquals("https://m.4khd.com/45ycnl",media.single().url)
        assertEquals("4KHD",media.single().archivePassword)
        assertFalse(media.any { it.url.contains("preview.jpg") })
        assertEquals(212,SelectedAlbumScope.advertisedCount(meta.title))
    }
}
