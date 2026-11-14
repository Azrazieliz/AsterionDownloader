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
    @Test fun unrelatedSitesUseExistingNativeOrGenericAdapter(){
        val page="https://kiutaku.com/7440"
        assertNull(GallerySiteOriginalExtractors.originals(
            Jsoup.parse("<main><img src='https://cdn.example/a.jpg'></main>",page),page))
    }
}
