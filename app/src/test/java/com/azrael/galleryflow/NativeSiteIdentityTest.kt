package com.azrael.galleryflow

import com.azrael.pixivdumpsync.SiteControls
import org.junit.Assert.*
import org.junit.Test

class NativeSiteIdentityTest {
    @Test fun everyNativeProviderHasOneSiteSubscription(){
        val sites=AdapterRegistry.defaultEntities().filter{it.kind=="site"}
        assertTrue("Native adapters must not create per-category site subscriptions",
            sites.groupBy{it.source}.all{(_,entities)->entities.size==1})
        assertEquals("4KHD",sites.single{it.source==SourceId.FOUR_K_HD}.displayName)
        assertEquals("site:all",
            sites.single{it.source==SourceId.FOUR_K_HD}.stableId)
        assertEquals("BuonDua",sites.single{it.source==SourceId.BUONDUA}.displayName)
        assertEquals("CosplayTele",
            sites.single{it.source==SourceId.COSPLAYTELE}.displayName)
    }

    @Test fun providerAliasesCannotCreateSeparateToggles(){
        assertEquals(SiteControls.key("https://buondua.com/"),
            SiteControls.key("https://buondua.net/albums"))
        assertEquals(SiteControls.key("https://www.4khd.com/pages/album"),
            SiteControls.key("https://www.4khd.com/pages/cosplay"))
        assertNotEquals(SiteControls.key("https://buondua.net"),
            SiteControls.key("https://www.4khd.com"))
    }
}
