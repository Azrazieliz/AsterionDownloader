package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class ProviderRetirementTest {
    @Test fun fourKGirlRetiredWithoutAffectingOtherGalleries(){
        assertTrue(ProviderRetirement.isRetired("https://4kgirl.com/"))
        assertTrue(ProviderRetirement.isRetired("https://www.4kgirl.com/example/"))
        assertFalse(ProviderRetirement.isRetired("https://4kgirl.com.example.test/"))
        assertFalse(ProviderRetirement.isRetired("https://www.4khd.com/"))
        assertFalse(ProviderRetirement.isRetired("https://cosplaytele.com/"))
        assertFalse(com.azrael.galleryflow.AdapterRegistry.available(
            com.azrael.galleryflow.SourceId.FOUR_K_GIRL))
        assertTrue(com.azrael.galleryflow.AdapterRegistry.defaultEntities().none{
            it.source==com.azrael.galleryflow.SourceId.FOUR_K_GIRL
        })
        assertThrows(com.azrael.galleryflow.AdapterException::class.java){
            com.azrael.galleryflow.AdapterRegistry.forUrl("https://www.4kgirl.com/example/")
        }
    }

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
        assertTrue(ProviderRetirement.isRetired("https://avple.tv/video/example"))
        assertTrue(ProviderRetirement.isRetired("https://m.avple.tv/watch/123"))
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
        assertFalse(ProviderRetirement.isRetired("https://avple.tv.attacker.example/"))
    }
}
