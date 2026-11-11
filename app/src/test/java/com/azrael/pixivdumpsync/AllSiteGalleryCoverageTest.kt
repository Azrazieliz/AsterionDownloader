package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents silently regressing to showing/checking only the first four sites. */
class AllSiteGalleryCoverageTest {
    @Test fun approvedPornCatalogExcludesRetiredProvider() {
        val sites=AlohaSources.by(AlohaArea.ADULT)
        assertEquals(18,sites.size)
        assertFalse(sites.any{it.host=="pornhits.com"})
        assertEquals(18,sites.map{SiteControls.key(it.url)}.distinct().size)
    }

    @Test fun publicListingRecognizesRealNestedMediaCovers() {
        val html="""
          <main>
            <div class="video-card"><a href="/videos/example-scene-12345">
              <img alt="Example clip title" data-src="/media/example-cover.jpg"/>
            </a></div>
            <nav><a href="/category/popular"><img alt="Popular" src="/logo.png"></a></nav>
          </main>
        """.trimIndent()
        val results=KyoraCreatorPreviews.parse(html,"https://example.com/")
        assertEquals(1,results.size)
        assertEquals("https://example.com/videos/example-scene-12345",results.single().url)
        assertEquals("https://example.com/media/example-cover.jpg",results.single().cover)
    }

    @Test fun failedPageStillHasItsOwnSourceIdentity() {
        val sites=AlohaSources.by(AlohaArea.ADULT)
        assertTrue(sites.any{it.name=="CUMS"})
        assertTrue(sites.any{it.name=="EPORNER"})
        assertTrue(sites.any{it.name=="FreePornVideos"})
    }
}
