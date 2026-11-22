package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class CosplayTeleExactIframeTransferTest {
    private val selected="https://cosplaytele.com/selected-cosplay-gallery/"
    private val iframe="https://cosplaytele.com/embed/selected-video-12345"

    @Test fun galleryVideoEmbeddedSelectorAcceptsOnlySelectedPublisherPlayer(){
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,iframe))
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://www.cosplaytele.com/player/selected-video-12345"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://cosplaytele.com/embed/selected-video-12345?ad=1"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://cosplaytele.com/embed/selected-video-12345#related"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://ads.example.org/embed/selected-video-12345"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            "https://cosplaytele.com/category/gallery/",iframe))
    }

    @Test fun selectedFirstPartyEmbedReturnsMediaNotUnsupportedIframe(){
        val html="""<html><main><div class="video-player">
            <video src="https://cdn.example.org/originals/selected-full.mp4"
                data-duration="780"></video>
        </div></main></html>"""
        val result=PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            selected,iframe,html)
        assertNotNull(result)
        assertEquals("https://cdn.example.org/originals/selected-full.mp4",
            result?.url)
        assertEquals(iframe,result?.referer)
    }

    @Test fun unrelatedOrUnselectedPlayerCanNeverProvideAnOriginal(){
        val html="""<main><div class="video-player">
            <video src="https://cdn.example.org/originals/unrelated.mp4"
                data-duration="780"></video></div></main>"""
        assertNull(PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            selected,"https://ads.example.org/embed/selected-video-12345",html))
        assertNull(PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            selected,"https://cosplaytele.com/category/selected-video-12345/",html))
        assertNull(PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            "https://cosplaytele.com/category/photos/",iframe,html))
    }

    @Test fun shortPreviewAndRecommendedSidebarVideoNeverPass(){
        val html="""<main><div class="video-player">
            <video src="https://cdn.example.org/preview/selected-short.mp4"
                data-duration="20"></video></div>
            <aside class="related"><video
               src="https://cdn.example.org/originals/unrelated-full.mp4"
               data-duration="800"></video></aside>
            </main>"""
        assertNull(PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            selected,iframe,html))
    }

    @Test fun ambiguousCompetingMainPlayerMediaIsNotSilentlySelected(){
        val html="""<main><div class="video-player">
            <video src="https://cdn.example.org/originals/first.mp4"
                data-duration="800"></video>
            <video src="https://cdn.example.org/originals/second.mp4"
                data-duration="800"></video>
        </div></main>"""
        assertNull(PublisherOriginalExtractors.cosplayTeleEmbeddedOriginal(
            selected,iframe,html))
    }
}
