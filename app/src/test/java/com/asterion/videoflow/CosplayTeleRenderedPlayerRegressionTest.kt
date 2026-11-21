package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class CosplayTeleRenderedPlayerRegressionTest {
    private val selected="https://cosplaytele.com/selected-gallery-with-video/"
    @Test fun exactPublisherBrowserSnapshotCanReachOriginalResolver(){
        // BrowserPageFetch's real compressed DOM shape.
        val html="""
          <html><body><h1>Selected set</h1>
            <main><div class="video-player">
                <video src="https://cdn.example.org/media/selected-full.mp4"
                       data-duration="235"></video>
              </div></main>
          </body></html>
        """
        val candidates=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(1,candidates.size)
        assertEquals("https://cdn.example.org/media/selected-full.mp4",
            candidates.single().url)
    }
    @Test fun unrelatedSidebarVideoIsNotSelected(){
        val html="""
          <main><aside class="related"><div class="video-player">
            <video src="https://cdn.example.org/other.mp4"></video>
          </div></aside></main>
        """
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,html).isEmpty())
    }
}
