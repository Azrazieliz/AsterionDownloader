package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class CustomWebsiteSearchRegressionTest {
    private val publisher=SearchProvider(SearchProviderKind.HTML,
        "https://unknown-publisher.example/find?q={query}","Custom site")
    @Test fun customSiteRetainsPublisherVideoArticleWithoutVideoPath(){
        val base="https://unknown-publisher.example/find?q=rare"
        val doc=Jsoup.parse("""
          <html><body><nav>
            <a href="/articles/rare-movie-news">Rare Movie News</a>
          </nav><main>
            <article class="post-card">
              <a href="/episodes/rare-movie-2026">
                <img src="/images/movie-poster.webp" alt="Rare Movie 2026"/>
                Rare Movie 2026 complete scene
              </a>
            </article>
            <article class="post-card">
              <a href="/category/rare-movie">Rare Movie Category</a>
            </article>
          </main></body></html>
        """,base)
        val found=KeywordSources.htmlPageHits(doc,base,publisher,
            "Rare Movie 2026",VideoSearchArea.PIRACY_DARKWEB)
        assertEquals(1,found.size)
        assertEquals("https://unknown-publisher.example/episodes/rare-movie-2026",
            found.single().url)
        assertFalse(SearchRanking.isVideoUrl(found.single().url))
    }
    @Test fun unconfiguredCustomSearchCannotImportRetiredSiteOrForeignCards(){
        val base="https://unknown-publisher.example/find?q=rare"
        val doc=Jsoup.parse("""
            <main><article>
                <a href="https://manyvids.com/videos/rare-movie-2026">
                    Rare Movie 2026
                </a>
                <a href="https://foreign.example/episodes/rare-movie-2026">
                    Rare Movie 2026
                </a>
            </article></main>
        """,base)
        assertTrue(KeywordSources.htmlPageHits(doc,base,publisher,
            "Rare Movie 2026",VideoSearchArea.PIRACY_DARKWEB).isEmpty())
    }
}
