package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentProviderPlacementTest {
    private val torrentHosts=setOf("onejav.com","sukebei.nyaa.si","xxxclub.to")

    @Test fun everyApprovedTorrentPublisherIsInTorrentsOnly() {
        val sources=AlohaSources.all.filter { it.host in torrentHosts }
        assertEquals(3,sources.size)
        assertTrue(sources.all { it.area==AlohaArea.TORRENTS })
        assertEquals(torrentHosts,sources.map { it.host }.toSet())
        assertTrue(AlohaSources.by(AlohaArea.ADULT,AlohaArea.HENTAI,
            AlohaArea.VIDEOS,AlohaArea.GAMES).none{it.host in torrentHosts})
    }

    @Test fun sameNativeTorrentFeedKeepsFirstPartyOrigins() {
        assertEquals("ONEJAV",TorrentFeedParser.source("https://onejav.com/torrent/abc"))
        assertEquals("SUKEBEI",TorrentFeedParser.source("https://sukebei.nyaa.si/view/1234"))
        assertEquals("XXXCLUB",TorrentFeedParser.source("https://xxxclub.to/torrents/details/480815"))
        assertEquals("https://xxxclub.to/torrents/browse/all/",
            TorrentFeedParser.home("XXXCLUB"))
        assertFalse(XXXClubTorrentSource.isDetail("https://xxxclub.to/torrents/browse/all/"))
        assertNull(TorrentFeedParser.source("https://xxxclub.to.attacker.example/torrents/details/480815"))
    }
}
