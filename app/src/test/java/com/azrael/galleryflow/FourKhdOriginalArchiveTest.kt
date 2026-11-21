package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

class FourKhdOriginalArchiveTest {
    @Test fun countAbsentDoesNotBlockAnOfficialSelectedArchive(){
        val selected="https://www.4khd.com/content/20/set-without-count.html"
        val fake=object:HttpClient(){
            override fun document(url:String,referer:String?):Document{
                assertEquals(selected,url)
                return Jsoup.parse("""
                    <main><article>
                      <h1>A cosplay photo set</h1>
                      <a href="https://m.4khd.com/realSet2026">
                        Download full HD original on TeraBox</a>
                      <div class="gallery"><img
                        src="https://cdn.4khd.com/preview.jpg"></div>
                    </article></main>
                """.trimIndent(),url)
            }
        }
        val (_,media)=FourKhdAdapter(fake).fetchGallery(GalleryRef(
            SourceId.FOUR_K_HD,"20/set-without-count",
            "site:all",selected,"A cosplay photo set"))
        assertEquals(1,media.size)
        assertEquals(MediaKind.ARCHIVE,media.single().kind)
        assertEquals("4KHD",media.single().provider)
        assertNull("Unknown publisher photo count must stay unknown",
            media.single().expectedArchivePhotos)
        assertFalse(media.any{it.url.contains("preview.jpg")})
    }

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
        assertEquals(212,media.single().expectedArchivePhotos)
        assertFalse(media.any { it.url.contains("preview.jpg") })
        assertEquals(212,SelectedAlbumScope.advertisedCount(meta.title))
        val (backfillMeta, backfillMedia)=FourKhdAdapter(fake).fetchGallery(
            album.copy(source=SourceId.FOUR_K_HD))
        assertEquals(SourceId.FOUR_K_HD,backfillMeta.source)
        assertEquals(SourceId.FOUR_K_HD,backfillMedia.single().source)
        assertEquals(media.single().url,backfillMedia.single().url)
    }
}
