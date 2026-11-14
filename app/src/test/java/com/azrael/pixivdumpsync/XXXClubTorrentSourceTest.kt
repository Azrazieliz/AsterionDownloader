package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class XXXClubTorrentSourceTest {
    private val page="https://xxxclub.to/torrents/details/480815"
    private val descriptor="https://xxxclub.to/torrents/download/6b5fe593cae9a62ebdc7635595db9af86c85613c"

    @Test fun currentProviderFormatIsTorrentNotVideoPlayer() {
        assertTrue(XXXClubTorrentSource.isDetail(page))
        assertTrue(XXXClubTorrentSource.isDescriptor(descriptor))
        assertEquals("480815",XXXClubTorrentSource.entryId(page))
        assertEquals("XXXCLUB",TorrentFeedParser.source(page))
        assertEquals("XXXCLUB",TorrentFeedParser.source(descriptor))
        assertEquals(descriptor,TorrentFeedParser.validTransfer(descriptor))
        assertEquals(descriptor,XXXClubTorrentSource.original(
            """<a href="/torrents/download/6b5fe593cae9a62ebdc7635595db9af86c85613c">Download torrent</a>""",page))
        assertFalse(SelectedVideoRouter.isSelectedVideo("ADULT",page,"torrent"))
    }

    @Test fun samePublisherDetailCardsUseNativeTorrentFeed() {
        val html="""<div class="torrent-list">
          <a href="/torrents/details/480815" title="Published release">Release</a>
          <a href="/torrents/browse/all/">Browse</a>
          </div>"""
        val entries=TorrentFeedParser.entries(
            Jsoup.parse(html,"https://xxxclub.to/torrents/browse/all/"),"XXXCLUB")
        assertEquals(1,entries.size)
        assertEquals(page,entries[0].page)
        assertEquals("DISCOVERED",entries[0].state)
    }

    @Test fun rejectAdvertisingAndCrossPublisherDownloads() {
        val html="""<a href="https://evil.example/torrents/download/6b5fe593cae9a62ebdc7635595db9af86c85613c">Download</a>
          <a href="/torrents/details/123456">Recommended</a>
          <a href="/torrents/download/deadbeef">Fake link</a>"""
        assertNull(XXXClubTorrentSource.original(html,page))
        assertNull(TorrentFeedParser.validTransfer(
            "https://not-xxxclub.to/torrents/download/6b5fe593cae9a62ebdc7635595db9af86c85613c"))
        assertFalse(XXXClubTorrentSource.isDetail(
            "https://xxxclub.to/torrents/browse/all/"))
    }
}
