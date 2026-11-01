package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class AlohaSourcesTest {
    @Test fun allFourExportsContainTheSame76UniqueSites(){
        val entries=AlohaSources.all
        assertEquals(76,entries.size)
        assertEquals(76,entries.map{it.url}.toSet().size)
        assertTrue(entries.all{it.url.startsWith("https://")})
        assertFalse(entries.any{it.url.contains("slot-id=")})
    }
    @Test fun sitesAreCategorizedAcrossTheExistingModules(){
        assertEquals(1,AlohaSources.by(AlohaArea.STUDY).size)
        assertTrue(AlohaSources.by(AlohaArea.ADULT,AlohaArea.HENTAI).size>=25)
        assertTrue(AlohaSources.by(AlohaArea.COMICS).any{it.host=="mangadex.org"})
        assertTrue(AlohaSources.by(AlohaArea.BOOKS).any{it.host=="mp4directs.com"})
        assertTrue(AlohaSources.by(AlohaArea.ASSETS).any{it.host=="db.bepis.moe"})
        assertTrue(AlohaSources.by(AlohaArea.GAMES).any{it.host=="nyaa.si"})
        assertTrue(AlohaSources.by(AlohaArea.COSPLAY).any{it.host=="cosplaytele.com"})
        assertTrue(AlohaSources.by(AlohaArea.ARTWORK).any{it.host=="pixiv.net"})
        val assigned=AlohaArea.values().sumOf{area->AlohaSources.by(area).size}
        assertEquals(76,assigned)
    }
}