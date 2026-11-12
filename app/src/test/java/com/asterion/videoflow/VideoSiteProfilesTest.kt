package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class VideoSiteProfilesTest {
    @Test fun approvedFullPornerRecognizesOnlyRealWatchPages(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://fullporner.com/watch/6af501862f7835591446c7d3"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://fullporner.com/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fullporner.com/player/6896933/6"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fullporner.com/pornstar/stacy-cruz"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fullporner.com/watch/invalid"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://fullporner.com.attacker.example/watch/6af501862f7835591446c7d3"))
    }
    @Test fun approvedFullPornerNumberedPaginationIsScopedToListing(){
        val home="https://fullporner.com/"
        val doc=Jsoup.parse("""<a href="/home/2">2</a>
            <a href="https://ads.example/home/2">Ad</a>""",home)
        assertEquals("https://fullporner.com/home/2",
            VideoSiteProfiles.next(doc,home,home))
        val artist="https://fullporner.com/pornstar/stacy-cruz"
        val doc2=Jsoup.parse("""<a href="/pornstar/stacy-cruz/2">2</a>
            <a href="/pornstar/other-person/2">Wrong profile</a>""",artist)
        assertEquals("https://fullporner.com/pornstar/stacy-cruz/2",
            VideoSiteProfiles.next(doc2,artist,artist))
        assertNull(VideoSiteProfiles.next(
            Jsoup.parse("""<a href="https://evil.example/home/2">2</a>""",home),
            home,home))
    }
    @Test fun retainedPornhubAndEpornerExactPagePaths(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.pornhub.com/view_video.php?viewkey=ph5abcd12"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/video123456/sample-title"))
        // XNXX retired at the owner's request; old history preserved.
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/hd-porn/ABCD123/video-title"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.pornhub.com/model/someone"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xvideos.com/tags/animation"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.eporner.com/"))
    }
    @Test fun ownerRetiredProvidersAreNoLongerAdmitted(){
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.xnxx.com/video-abc123/a-video"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.porntrex.com/video/an-episode"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.peekvids.com/videos/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.tnaflix.com/video/example"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://pornone.com/dirty-talk/example-video/279567143/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://www.sunporno.com/v/115776/example-video/"))
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
            "https://watchporn.to/video/1234/name",
            "https://hentaibros.net/watch/example")){
            assertTrue(url,VideoSiteProfiles.isVideoPage(url))
        }
        assertEquals(20,VideoSiteProfiles.coveredHosts.size)
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
    @Test fun liveHentaiProviderPostRoutesAreNotReleaseCalendarsOrCategories(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://3dhentaix.com/loving-mother-1-8-by-japs8005/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://3dhentaix.com/phantom/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://3dhentaix.com/anal/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://3dhentaix.com/explore/categories/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://mp4hentai.com/2026/11/si-give-me-more-segs/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://mp4hentai.com/2026/07/upcoming-october-2026-hentai-release/"))
    }
    @Test fun paginationDoesNotCrossToOtherDomain(){
        val home="https://www.eporner.com/"
        val doc=Jsoup.parse("""<nav class="pagination">
          <a class="next" href="/page/2/">Next</a>
          <a rel="next" href="https://ads.example.com/page/2">Ad</a>
        </nav>""",home)
        assertEquals("https://www.eporner.com/page/2/",
            VideoSiteProfiles.next(doc,home,home))
        assertNull(VideoSiteProfiles.next(
            Jsoup.parse("<a class='next' href='https://evil.example/page/3'>Next</a>",home),
            home,home))
    }
}
