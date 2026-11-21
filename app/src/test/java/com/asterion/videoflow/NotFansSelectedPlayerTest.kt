package com.asterion.videoflow

import com.azrael.pixivdumpsync.BrowserPageFetch
import org.junit.Assert.*
import org.junit.Test

class NotFansSelectedPlayerTest {
    private val selected="https://notfans.com/videos/selected-work-12345/"
    @Test fun selectedPostIsEligibleForPublicBrowserAndExactProvider(){
        assertTrue(VideoSiteProfiles.isVideoPage(selected))
        assertTrue(BrowserPageFetch.publicEligible(selected))
        assertFalse(BrowserPageFetch.publicEligible(
            "https://notfans.com.evil.example/videos/selected-work-12345/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://notfans.com/"))
    }
    @Test fun onlyArticlePlayerCanSupplyOriginalNotGlobalOrRelatedVideo(){
        val html="""
          <html><head><meta itemprop="contentUrl"
              content="https://cdn.example.com/unrelated.mp4"/></head><body>
            <main><article><div class="video-player">
              <video src="https://cdn.example.com/selected-work.mp4"></video>
            </div></article></main>
            <aside class="related">
              <div class="video-player">
                <video src="https://cdn.example.com/preview.mp4"></video>
              </div>
            </aside>
          </body></html>
        """
        val sources=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(1,sources.size)
        assertEquals("https://cdn.example.com/selected-work.mp4",sources.single().url)
    }
    @Test fun fullVideoMustNotBeReplacedByUncorroboratedShortPreview(){
        assertTrue(UnverifiedSelectedPreviewGuard.shouldHold(selected,20_000L,null))
        assertFalse(UnverifiedSelectedPreviewGuard.shouldHold(selected,180_000L,null))
    }
}
