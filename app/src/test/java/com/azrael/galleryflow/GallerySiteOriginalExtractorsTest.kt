package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class GallerySiteOriginalExtractorsTest {
    @Test fun pornpicsSelectedGalleryExcludesSidebarAdsAndThumbnails(){
        val page="https://www.pornpics.com/galleries/example/"
        val html="""<main><div id="gallery">
            <img data-original="https://cdn.example/gallery/1.jpg"
                 src="https://cdn.example/thumbs/1.jpg"/>
            <img src="https://cdn.example/thumbs/2.jpg"/>
            </div></main>
            <aside><img src="https://cdn.example/ads/banner.jpg"/></aside>"""
        val doc=Jsoup.parse(html,page)
        assertEquals(listOf("https://cdn.example/gallery/1.jpg"),
            GallerySiteOriginalExtractors.originals(doc,page)?.map{it.url})
    }
    @Test fun celebSelectedGalleryUsesPublishedFullImageAnchor(){
        val page="https://celeb.st/creator/test/images"
        val html="""<main><div class="media-gallery">
            <a href="https://cdn.example/media/large001.webp">
              <img src="https://cdn.example/thumbs/small001.webp"/>
            </a></div></main>"""
        val doc=Jsoup.parse(html,page)
        assertEquals(listOf("https://cdn.example/media/large001.webp"),
            GallerySiteOriginalExtractors.originals(doc,page)?.map{it.url})
    }
    @Test fun pornpicsPublisherOriginalAnchorsWithoutFixedGalleryId() {
        val page="https://www.pornpics.com/galleries/a-real-album-12345/"
        val doc=Jsoup.parse("""
            <main>
              <a href="https://cdn.example/photos/001.jpg">Full image 1</a>
              <a href="https://cdn.example/photos/002.jpg">Full image 2</a>
              <div class="related"><a href="https://cdn.example/other/003.jpg">
                <img src="https://cdn.example/other/thumb003.jpg"/></a></div>
            </main>
        """.trimIndent(),page)
        assertEquals(listOf("https://cdn.example/photos/001.jpg",
            "https://cdn.example/photos/002.jpg"),
            GallerySiteOriginalExtractors.originals(doc,page)?.map{it.url})
    }

    @Test fun exactPornpicsAlbumScopeBeatsGenericMainContent() {
        val page="https://www.pornpics.com/galleries/album-7/"
        val doc=Jsoup.parse("""
            <main><div id="gallery">
              <a href="https://cdn.example/album/001.jpg">Original</a>
            </div>
            <div class="more"><a href="https://cdn.example/other/999.jpg">
              Other album advertised here</a></div></main>
        """.trimIndent(),page)
        assertEquals(listOf("https://cdn.example/album/001.jpg"),
            GallerySiteOriginalExtractors.originals(doc,page)?.map{it.url})
    }

    @Test fun pornpicsCategoryCannotBeMistakenForSelectedGallery() {
        val doc=Jsoup.parse("""
            <main><a href="https://cdn.example/photos/001.jpg">Advertised image</a></main>
        """.trimIndent(),"https://www.pornpics.com/channels/channel/")
        assertTrue(GallerySiteOriginalExtractors.originals(
            doc,"https://www.pornpics.com/channels/channel/").orEmpty().isEmpty())
    }

    @Test fun unrelatedSitesUseExistingNativeOrGenericAdapter(){
        val page="https://kiutaku.com/7440"
        assertNull(GallerySiteOriginalExtractors.originals(
            Jsoup.parse("<main><img src='https://cdn.example/a.jpg'></main>",page),page))
    }
}
