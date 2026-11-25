package com.azrael.pixivdumpsync

import com.asterion.videoflow.VideoSiteProfiles
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** DSYSAV's public Next.js SSR is an empty 200 HTML shell. Its real
 * publisher-owned watch cards must first render in the authorized browser.
 * Catalog candidates are never classified as downloaded original files.
 */
class DsysavBrowserCatalogTest {
    private val home="https://dsysav.com/en"
    private val selected="https://dsysav.com/en/watch?v=MxWT7NmlUoT"

    @Test fun publicDsysavCatalogCanUseExistingAuthorizedWebView(){
        assertTrue(BrowserPageFetch.publicEligible(home))
        assertTrue(BrowserPageFetch.publicEligible(selected))
        assertFalse(BrowserPageFetch.publicEligible(
            "https://dsysav.com.evil.example/en"))
        assertFalse(BrowserPageFetch.publicEligible(
            "http://dsysav.com/en"))
    }

    @Test fun onlyPublishedWatchCardBecomesVideoCandidate(){
        val page=Jsoup.parse("""
            <main><article><h2><a href="/en/watch?v=MxWT7NmlUoT"
                title="Selected publisher video scene">
                Selected publisher video scene</a></h2></article>
                <a href="/en/watch?v=wrong">Unrelated invalid watch ID</a>
                <a href="/en/category?tag=preview">Category navigation</a>
            </main>
        """.trimIndent(),home)
        val found=SourceIndexParser.entries(page,home,"ADULT",123L)
        assertEquals(1,found.size)
        assertEquals(selected,found.single().url)
        assertEquals("video",found.single().kind)
    }

    @Test fun emptyBrowserFallbackIsAnErrorNotAnEmptySuccessfulIndex(){
        val empty=Jsoup.parse("<html><body><div id='__next'></div></body></html>",home)
        assertThrows(IllegalStateException::class.java){
            SourceIndexParser.requireRenderedCatalog(home,home,"ADULT",empty)
        }
        val real=Jsoup.parse("<a href='/en/watch?v=abcdefghi'>Publisher item</a>",home)
        SourceIndexParser.requireRenderedCatalog(home,home,"ADULT",real)
    }

    @Test fun emptyServerShellNeverPretendsToContainVideo(){
        val page=Jsoup.parse("""
            <html><head><title>DSAV</title></head>
            <body><div id="__next"></div></body></html>
        """.trimIndent(),home)
        assertTrue(SourceIndexParser.entries(page,home,"ADULT",123L).isEmpty())
        assertTrue(VideoSiteProfiles.isVideoPage(selected))
        assertFalse(VideoSiteProfiles.isVideoPage(home))
    }
}
