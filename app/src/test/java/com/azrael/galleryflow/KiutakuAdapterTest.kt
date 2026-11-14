package com.azrael.galleryflow

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KiutakuAdapterTest {
    @Test
    fun selectedAlbumUsesArticleFulltextNotNeighborRecommendations() {
        val doc=Jsoup.parse("""
            <article>
              <div class="article-fulltext"><img src="https://cdn.example/7440/001.jpg"/></div>
              <div class="related"><img src="https://cdn.example/other/999.jpg"/></div>
            </article>
        """.trimIndent(),"https://kiutaku.com/7440")
        assertEquals(listOf("https://cdn.example/7440/001.jpg"),
            KiutakuAdapter(HttpClient()).imageCandidates(doc))
    }

    @Test
    fun publisherEndPageExpandsMissingIntermediatePages() {
        val doc=Jsoup.parse("""
            <div class="pagination-list"><a href="?page=2">2</a>
                <a href="?page=7">End</a><span>7</span></div>
        """.trimIndent(),"https://kiutaku.com/7440")
        assertEquals(7,KiutakuAdapter(HttpClient())
            .advertisedGalleryPage(doc,"https://kiutaku.com/7440"))
    }

    @Test
    fun imageCandidatesPreferGalleryImagesAndIgnoreIcons() {
        val html = """
            <html><body><main>
              <img src="/assets/logo.png" width="80" height="80" alt="logo">
              <img data-src="https://img.example/7333/001.jpg?size=full" alt="Example photo 1-0">
              <img src="https://img.example/7333/002.webp" alt="Example photo 1-1">
            </main></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, "https://kiutaku.com/7333")
        val adapter = KiutakuAdapter(HttpClient())
        val urls = adapter.imageCandidates(doc)
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("001.jpg"))
        assertTrue(urls[1].contains("002.webp"))
    }
}
