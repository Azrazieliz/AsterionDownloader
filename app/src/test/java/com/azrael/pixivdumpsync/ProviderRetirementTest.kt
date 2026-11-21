package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ProviderRetirementTest {
    @Test fun removedProviderAndSubdomainAreNotActive(){
        assertTrue(ProviderRetirement.isRetired("https://www.pornhits.com/main/"))
        assertTrue(ProviderRetirement.isRetired("https://m.pornhits.com/video/example"))
        assertTrue(ProviderRetirement.isRetired("https://pornhits.com/video/example"))
        assertTrue(ProviderRetirement.isRetired("https://www.omg.porn/fr/"))
        assertTrue(ProviderRetirement.isRetired("https://www.omg.xxx/fr/"))
        assertTrue(ProviderRetirement.isRetired("https://www.xvideos.com/video123"))
        assertTrue(ProviderRetirement.isRetired("https://www.peekvids.com/video/a"))
        assertTrue(ProviderRetirement.isRetired("https://www.tnaflix.com/video/a"))
        assertTrue(ProviderRetirement.isRetired("https://camstreams.tv/"))
        assertTrue(ProviderRetirement.isRetired("https://www.pornpics.com/galleries/example/"))
        assertTrue(ProviderRetirement.isRetired("https://celeb.st/feed/hot"))
        assertFalse(ProviderRetirement.isRetired("https://pimpbunny.com/models/"))
        assertFalse(ProviderRetirement.isRetired("https://pimpbunny.com/videos/"))
        assertTrue(ProviderRetirement.isRetired("https://www.camstreams.tv/videos/1363858/selected/"))
        assertTrue(ProviderRetirement.isRetired("https://www.onscreens.me/"))
        assertTrue(ProviderRetirement.isRetired("https://chaturbate.com/"))
        assertTrue(ProviderRetirement.isRetired("https://www.chaturbate.com/performer/"))
        assertFalse(ProviderRetirement.isRetired("https://archivebate.com/"))
        assertFalse(ProviderRetirement.isRetired("https://www.archivebate.com/watch/16458050"))
    }
    @Test fun lookalikeAndUnrelatedHostsRemainIndependent(){
        assertFalse(ProviderRetirement.isRetired("https://pornhits.com.evil.example/"))
        assertFalse(ProviderRetirement.isRetired("https://mypornhits.com/"))
        assertFalse(ProviderRetirement.isRetired("https://omg.porn.attacker.example/"))
        assertFalse(ProviderRetirement.isRetired("https://chaturbate.com.evil.example/"))
        assertFalse(ProviderRetirement.isRetired("https://hentaibros.net/"))
    }
}
