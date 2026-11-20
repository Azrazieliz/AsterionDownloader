package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AlohaSourcesTest {
    @Test fun userApprovedBookmarksOnly() {
        val sites=AlohaSources.all
        assertEquals(44,sites.size)
        assertEquals(44,sites.map{it.url}.toSet().size)
        assertTrue(sites.all{it.url.startsWith("https://")})
        assertTrue(sites.none{it.url.contains("slot-id=")})
        assertTrue(sites.none{it.area==AlohaArea.REFERENCE ||
            it.area==AlohaArea.STUDY || it.area==AlohaArea.ARTWORK})
        assertEquals(6,AlohaSources.by(AlohaArea.COSPLAY).size)
        assertEquals(22,AlohaSources.by(AlohaArea.ADULT).size)
        assertTrue(AlohaSources.by(AlohaArea.VIDEOS).isEmpty())
        assertEquals(3,AlohaSources.by(AlohaArea.TORRENTS).size)
        assertEquals(setOf("onejav.com","sukebei.nyaa.si","xxxclub.to"),
            AlohaSources.by(AlohaArea.TORRENTS).map{it.host}.toSet())
        assertFalse(sites.any{it.host=="cums.net"})
        assertTrue(sites.any{it.host=="camstreams.tv"})
        assertFalse(sites.any{it.host=="onscreens.me"})
        assertTrue(AlohaSources.by(AlohaArea.ADULT).any{it.host=="archivebate.com"})
        assertTrue(sites.none{it.host in setOf("mp4hentai.com","hentaihaven.xxx","helloporn.com")})
        assertEquals(5,AlohaSources.by(AlohaArea.HENTAI).size)
        assertFalse(sites.any{it.host in setOf("xvideos.com","peekvids.com","tnaflix.com")})
        assertEquals(3,AlohaSources.by(AlohaArea.COMICS).size)
        assertEquals(4,AlohaSources.by(AlohaArea.BOOKS).size)
        assertTrue(AlohaSources.by(AlohaArea.GAMES).isEmpty())
        assertEquals(1,AlohaSources.by(AlohaArea.ASSETS).size)
    }
    @Test fun onlyMissingSourcesAreShownInAlohaAdditions() {
        val newSites=AlohaSources.additions
        assertEquals(35,newSites.size)
        assertEquals(35,newSites.map{it.host}.toSet().size)
        assertTrue(newSites.any{it.host=="fullporner.com"})
        assertTrue(newSites.any{it.host=="porneec.com"})
        assertTrue(newSites.any{it.host=="taiav.com"})
        assertTrue(newSites.any{it.host=="notfans.com"})
        assertTrue(newSites.any{it.host=="archivebate.com"})
        assertTrue(newSites.any{it.host=="mangadex.org"})
        assertFalse(newSites.any{it.host=="sushiscan.net"})
        assertTrue(newSites.any{it.host=="nhentai.xxx"})
        assertTrue(newSites.any{it.host=="nhentai.net"})
        assertTrue(newSites.any{it.host=="freewebnovel.com"})
        assertTrue(newSites.any{it.host=="onejav.com"})
        assertFalse(newSites.any{it.host=="pornhits.com" || it.host=="omg.porn" || it.host=="omg.xxx"})
        assertFalse(newSites.any{it.host=="cosplaytele.com"})
        assertFalse(newSites.any{it.host=="sukebei.nyaa.si"})
        assertFalse(newSites.any{it.host=="db.bepis.moe"})
    }
}
