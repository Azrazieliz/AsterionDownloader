package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class TorrentSourceBackfillTest {
    @Test fun onejavIndexesTorrentDetailsNotSiteNavigation(){
        val home=TorrentFeedParser.onejav
        val doc=Jsoup.parse("""<main>
          <article><a href="/torrent/ymds282">YMDS-282 release</a>
            <a href="magnet:?xt=urn:btih:0123456789012345678901234567890123456789">
              Magnet</a></article>
          <a href="/category/weekly">Categories</a>
          <a href="https://ads.example.org/torrent/ymds282">Advertisement</a>
        </main>""",home)
        val found=TorrentFeedParser.entries(doc,"ONEJAV")
        assertEquals(1,found.size)
        assertTrue(found.single().page.endsWith("/torrent/ymds282"))
        assertTrue(found.single().download!!.startsWith("magnet:"))
    }
    @Test fun sukebeiKeepsActualIdAndTorrentFile(){
        val base="https://sukebei.nyaa.si/"
        val html="""<table><tr>
          <td><a href="/view/1234567">Artist sample 2026 release</a></td>
          <td><a href="/download/1234567.torrent">Torrent file</a></td>
          <td><a href="magnet:?xt=urn:btih:0123456789012345678901234567890123456789">Magnet</a></td>
        </tr></table>"""
        val found=TorrentFeedParser.entries(Jsoup.parse(html,base),"SUKEBEI")
        assertEquals(1,found.size)
        assertTrue(found.single().page.endsWith("/view/1234567"))
        assertEquals("https://sukebei.nyaa.si/download/1234567.torrent",
            TorrentFeedParser.validTransfer(
                "https://sukebei.nyaa.si/download/1234567.torrent"))
    }
    @Test fun onlySupportedMetadataEndpoints(){
        assertNull(TorrentFeedParser.validTransfer(
            "https://hostile.invalid/download/12.torrent"))
        assertNull(TorrentFeedParser.validTransfer(
            "https://onejav.com/login"))
        assertNotNull(TorrentFeedParser.validTransfer(
            "magnet:?xt=urn:btih:0123456789012345678901234567890123456789"))
        assertEquals("ONEJAV",TorrentFeedParser.source(TorrentFeedParser.onejav))
        assertEquals("SUKEBEI",TorrentFeedParser.source(TorrentFeedParser.sukebei))
    }
    @Test fun onejavAndSukebeiHistoricalPagesDoNotCrossDomains(){
        val base="https://onejav.com/"
        val doc=Jsoup.parse("""<nav class="pagination">
          <a class="next" href="/page/2">More torrents</a>
          <a rel="next" href="https://hostile.invalid/page/3">Advertisement</a>
        </nav>""",base)
        assertEquals("https://onejav.com/page/2",
            TorrentFeedParser.next(doc,"ONEJAV",base))
    }
}
