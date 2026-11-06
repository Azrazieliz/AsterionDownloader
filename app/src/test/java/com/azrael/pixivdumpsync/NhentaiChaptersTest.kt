package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class NhentaiChaptersTest {
    @Test fun onlyTwoApprovedHostsAndRealGalleryPaths(){
        assertTrue(NhentaiChapters.isGallery("https://nhentai.net/g/123456/"))
        assertTrue(NhentaiChapters.isGallery("https://nhentai.xxx/g/123456"))
        assertFalse(NhentaiChapters.isGallery("https://evil.example/g/123456/"))
        assertFalse(NhentaiChapters.isGallery("http://nhentai.net/g/123456/"))
        assertFalse(NhentaiChapters.isGallery("https://nhentai.net/tag/123456"))
    }
    @Test fun pageEnumerationOrdersLinksAndRejectsForeignGallery(){
        val root="https://nhentai.net/g/123456/"
        val doc=Jsoup.parse("""<div id="thumbnail-container">
          <div class="gallerythumb"><a href="/g/123456/3/"><img src="/t3.jpg"/></a></div>
          <div class="gallerythumb"><a href="/g/123456/1/"><img src="/t1.jpg"/></a></div>
          <div class="gallerythumb"><a href="/g/222/2/"><img src="/wrong.jpg"/></a></div>
          <div class="gallerythumb"><a href="/g/123456/2/"><img src="/t2.jpg"/></a></div>
          </div><div>3 pages</div>""",root)
        val hits=NhentaiChapters.pages(doc,root)
        assertEquals(3,hits.size)
        assertEquals(listOf("Page 1","Page 2","Page 3"),hits.map{it.title})
    }
    @Test(expected=IllegalArgumentException::class)
    fun partialGalleryIndexNeverExported(){
        val root="https://nhentai.net/g/123456/"
        val doc=Jsoup.parse("""<div id="thumbnail-container">
          <div class="gallerythumb"><a href="/g/123456/1/">First</a></div>
          <div class="gallerythumb"><a href="/g/123456/3/">Last</a></div>
          </div>""",root)
        NhentaiChapters.pages(doc,root)
    }
    @Test fun originalPageImageExcludesThumbnails(){
        val doc=Jsoup.parse("""<div class="sidebar">
            <img data-src="/thumb-5.jpg"/></div>
            <section id="image-container"><img id="image"
              data-src="https://i.nhentai.net/galleries/112/5.jpg"/></section>""",
            "https://nhentai.net/g/123456/5/")
        assertEquals(listOf("https://i.nhentai.net/galleries/112/5.jpg"),
            NhentaiChapters.pageImage(doc))
    }
}
