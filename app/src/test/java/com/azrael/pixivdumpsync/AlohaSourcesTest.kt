package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AlohaSourcesTest {
    @Test fun userApprovedBookmarksOnly() {
        val sites=AlohaSources.all
        assertEquals(38,sites.size)
        assertEquals(38,sites.map{it.url}.toSet().size)
        assertTrue(sites.all{it.url.startsWith("https://")})
        assertTrue(sites.none{it.url.contains("slot-id=")})
        assertTrue(sites.none{it.area==AlohaArea.REFERENCE ||
            it.area==AlohaArea.STUDY || it.area==AlohaArea.ARTWORK})
        assertEquals(6,AlohaSources.by(AlohaArea.COSPLAY).size)
        assertEquals(15,AlohaSources.by(AlohaArea.ADULT).size)
        assertEquals(1,AlohaSources.by(AlohaArea.VIDEOS).size)
        assertEquals("onejav.com",AlohaSources.by(AlohaArea.VIDEOS).single().host)
        assertTrue(sites.none{it.host=="cums.net"})
        assertEquals(7,AlohaSources.by(AlohaArea.HENTAI).size)
        assertFalse(sites.any{it.host in setOf("xvideos.com","peekvids.com","tnaflix.com")})
        assertEquals(3,AlohaSources.by(AlohaArea.COMICS).size)
        assertEquals(4,AlohaSources.by(AlohaArea.BOOKS).size)
        assertEquals(1,AlohaSources.by(AlohaArea.GAMES).size)
        assertEquals(1,AlohaSources.by(AlohaArea.ASSETS).size)
    }
    @Test fun onlyMissingSourcesAreShownInAlohaAdditions() {
        val newSites=AlohaSources.additions
        assertEquals(28,newSites.size)
        assertEquals(28,newSites.map{it.host}.toSet().size)
        assertTrue(newSites.any{it.host=="fullporner.com"})
        assertTrue(newSites.any{it.host=="notfans.com"})
        assertTrue(newSites.any{it.host=="archivebate.com"})
        assertTrue(newSites.any{it.host=="sushiscan.net"})
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
