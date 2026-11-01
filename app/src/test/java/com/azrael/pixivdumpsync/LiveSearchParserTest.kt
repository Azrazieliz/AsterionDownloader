package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Deterministic fixtures for the site page structures observed on 2026-11-01. */
class LiveSearchParserTest {
    @Test fun hentaiTvSeriesCardsBecomeActualResults() {
        val url = "https://hentai.tv/series?search=kanojo"
        val page = Jsoup.parse("""
            <main><a class="cat-card" href="/series/kanojo-saimin">
            <img alt="Kanojo Saimin"><span class="cat-title">Kanojo Saimin</span>
            </a><a class="cat-card" href="/series/unrelated">
            <span class="cat-title">Other Show</span></a></main>
        """.trimIndent(),url)
        val provider = SearchProvider(SearchProviderKind.HTML,url,"HentaiTV")
        val hits = KeywordSources.htmlPageHits(page,url,provider,"kanojo",VideoSearchArea.HENTAI)
        assertEquals(1,SearchRanking.deduplicate("kanojo",hits).size)
        assertEquals("https://hentai.tv/series/kanojo-saimin",
            SearchRanking.deduplicate("kanojo",hits).first().url)
    }

    @Test fun hentaiHavenWatchCardsAreRelevant() {
        val url = "https://hentaihaven.xxx/?s=kanojo"
        val page = Jsoup.parse("""
            <div><a href="/watch/kanojo-no-tomodachi/"
               aria-label="Kanojo no Tomodachi">
               <img alt="Kanojo no Tomodachi"></a></div>
            <a href="/watch/unrelated/" aria-label="Other title">Other title</a>
        """.trimIndent(),url)
        val provider = SearchProvider(SearchProviderKind.HTML,url,"HentaiHaven")
        val hits = KeywordSources.htmlPageHits(page,url,provider,"kanojo",VideoSearchArea.HENTAI)
        assertEquals(1,SearchRanking.deduplicate("kanojo",hits).size)
    }
}
