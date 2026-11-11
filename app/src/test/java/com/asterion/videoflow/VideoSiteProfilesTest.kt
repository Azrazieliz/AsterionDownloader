package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VideoSiteProfilesTest {
    @Test fun pornhubXvideosXnxxAndEpornerExactPagePaths(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.pornhub.com/view_video.php?viewkey=ph5abcd12"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/video123456/sample-title"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.xnxx.com/video-abc123/sample-title"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/hd-porn/ABCD123/video-title"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.pornhub.com/model/someone"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/tags/animation"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/"))
    }
    @Test fun javGuruBlogPostIsDetectedButCategoryIsNot(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://jav.guru/1076295/start-663-sample-post/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://jav.guru/category/uncensored/"))
    }
    @Test fun hentaiAndVideoSourceHostsHaveIndependentProfiles(){
        for(url in listOf(
            "https://rule34video.com/video/example",
            "https://mp4hentai.com/video/example",
            "https://porntrex.com/video/example",
            "https://watchporn.to/video/1234/name",
            "https://hentaibros.net/watch/example")){
            assertTrue(url,VideoSiteProfiles.isVideoPage(url))
        }
        assertEquals(24,VideoSiteProfiles.coveredHosts.size)
        assertFalse(VideoSiteProfiles.isVideoPage("https://pornhits.com/video/retired"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://hentaibros.net/muramata-san-no-aijou-episode-2/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://hentaibros.net/latest-videos/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.omg.porn/fr/2257/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.omg.porn/fr/"))
    }
    @Test fun paginationDoesNotCrossToOtherDomain(){
        val home="https://www.xvideos.com/"
        val doc=Jsoup.parse("""<nav class="pagination">
          <a class="next" href="/page/2/">Next</a>
          <a rel="next" href="https://ads.example.com/page/2">Ad</a>
        </nav>""",home)
        assertEquals("https://www.xvideos.com/page/2/",
            VideoSiteProfiles.next(doc,home,home))
        assertNull(VideoSiteProfiles.next(
            Jsoup.parse("<a class='next' href='https://evil.example/page/3'>Next</a>",home),
            home,home))
    }
}
