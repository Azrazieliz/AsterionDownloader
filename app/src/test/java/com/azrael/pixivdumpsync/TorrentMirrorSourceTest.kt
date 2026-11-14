package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Offline fixtures prove source registration and release filtering without
 * claiming any live torrent server is available.
 */
class TorrentMirrorSourceTest {
    @Test fun recognizedSourcesAreProviderSpecific(){
        assertEquals("NYAA_MIRROR",
            TorrentFeedParser.source("https://nyaa.v300.eu.org/"))
        assertEquals("SUKEBEI",
            TorrentFeedParser.source("https://sukebei.nyaa.si/"))
        assertEquals("ONEJAV",
            TorrentFeedParser.source("https://onejav.com/"))
        assertNull(TorrentFeedParser.source("https://example.org/"))
    }
    @Test fun mirrorListingDoesNotTreatNavigationAsTorrent(){
        val html="""
          <table>
            <tr><td><a href="/view/123">Actual game release</a></td></tr>
            <tr><td><a href="/category/123">Game category page</a></td></tr>
            <tr><td><a href="/view/nonnumeric">Not a release</a></td></tr>
          </table>
        """.trimIndent()
        val doc=Jsoup.parse(html,"https://nyaa.v300.eu.org/")
        val results=TorrentFeedParser.entries(doc,"NYAA_MIRROR")
        assertEquals(1,results.size)
        assertEquals("Actual game release",results[0].title)
        assertEquals("https://nyaa.v300.eu.org/view/123",results[0].page)
    }
    @Test fun mirrorTransferAndCrossSiteUrlsAreDistinguished(){
        assertEquals("https://nyaa.v300.eu.org/download/123.torrent",
            TorrentFeedParser.validTransfer(
                "https://nyaa.v300.eu.org/download/123.torrent"))
        assertNull(TorrentFeedParser.validTransfer(
            "https://untrusted.example/download/123.torrent"))
        assertNull(TorrentFeedParser.validTransfer(
            "https://nyaa.v300.eu.org/languages/english"))
    }
}
