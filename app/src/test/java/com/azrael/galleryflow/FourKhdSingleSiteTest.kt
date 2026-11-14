package com.azrael.galleryflow

import com.azrael.pixivdumpsync.SiteControls
import org.junit.Assert.*
import org.junit.Test

/** Regression: source folders are not separate 4KHD subscriptions. */
class FourKhdSingleSiteTest {
    @Test fun oneSubscriptionCoversBothSections(){
        val adapter=FourKhdAdapter(HttpClient())
        val providers=adapter.defaultEntities()
        assertEquals(1,providers.size)
        assertEquals("4KHD",providers.single().displayName)
        assertEquals("site:all",providers.single().stableId)
        assertEquals("https://www.4khd.com/",providers.single().canonicalUrl)
        assertEquals(providers.single(),adapter.resolveEntity("https://www.4khd.com/pages/album"))
        assertEquals(providers.single(),adapter.resolveEntity("https://www.4khd.com/pages/cosplay"))
    }
    @Test fun preferencesArePerSiteNotPerAlbum(){
        val root="https://www.4khd.com/"
        assertEquals("4khd.com",SiteControls.key(root))
        assertEquals(SiteControls.key(root),
            SiteControls.key("https://www.4khd.com/pages/album"))
        assertEquals(SiteControls.key(root),
            SiteControls.key("https://4khd.com/pages/cosplay"))
        assertNotEquals(SiteControls.key(root),
            SiteControls.key("https://www.cosplaytele.com/"))
    }
}
