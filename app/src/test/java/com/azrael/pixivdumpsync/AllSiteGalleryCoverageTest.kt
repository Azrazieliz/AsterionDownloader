package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prevents silently regressing to showing/checking only the first four sites. */
class AllSiteGalleryCoverageTest {
    @Test fun approvedPornCatalogExcludesRetiredProvider() {
        val sites=AlohaSources.by(AlohaArea.ADULT)
        assertEquals(15,sites.size)
        assertFalse(sites.any{it.host in setOf("pornhits.com","omg.porn","omg.xxx",
            "porntrex.com","xnxx.com","pornone.com","sunporno.com")})
        assertEquals(15,sites.map{SiteControls.key(it.url)}.distinct().size)
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

    @Test fun hentaibrosNativeSingleSlugCardsRemainVisible() {
        val base="https://hentaibros.net/"
        val html="""<article class="loop-video thumb-block video-preview-item">
          <a href="/muramata-san-no-aijou-episode-2/"
             title="Muramata-san no Aijou episode 2">
            <div class="post-thumbnail">
              <div class="post-thumbnail-container">
                <img class="video-main-thumb"
                     src="/wp-content/uploads/2026/10/Muramata-san-no-Aijou-02.jpg"
                     alt="Muramata-san no Aijou episode 2">
              </div>
            </div>
          </a>
        </article>"""
        val covers=KyoraCreatorPreviews.parse(html,base)
        assertEquals(1,covers.size)
        assertTrue(AsterionAdultHub.publicMedia(
            covers.single().url,covers.single().title,covers.single().cover))
        assertTrue(com.asterion.videoflow.VideoSiteProfiles.isVideoPage(
            covers.single().url))
    }

    @Test fun javideoLocalizedMovieSlugIsARecognizedPage() {
        val url="https://javideo.net/en/fc2ppv-4994295"
        assertTrue(com.asterion.videoflow.VideoSiteProfiles.isVideoPage(url))
        assertTrue(AsterionAdultHub.publicMedia(url,"FC2PPV-4994295",
            "https://cdn.javideo.net/preview/real-cover.jpg"))
    }

    @Test fun failedPageStillHasItsOwnSourceIdentity() {
        val sites=AlohaSources.by(AlohaArea.ADULT)
        assertTrue(sites.any{it.name=="CUMS"})
        assertTrue(sites.any{it.name=="EPORNER"})
        assertTrue(sites.any{it.name=="FreePornVideos"})
    }
}
