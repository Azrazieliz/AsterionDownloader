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
    @Test fun helloPornActualFirstPartyVideoCardRoutesAreSelected() {
        val selected="https://helloporn.com/video-28699-fakedrivingschool-naughty-student-late-for-her-exam-j"
        assertFalse(VideoSiteProfiles.isVideoPage(selected))
        assertFalse(PublisherOriginalExtractors.owns(selected))
        assertFalse(VideoSiteProfiles.isVideoPage("https://helloporn.com/videos"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://helloporn.com/channels/213-fake-driving-school"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://helloporn.com/video-latest"))
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
            "https://watchporn.to/video/1234/name",
            "https://hentaibros.net/watch/example")){
            assertTrue(url,VideoSiteProfiles.isVideoPage(url))
        }
        assertEquals(29,VideoSiteProfiles.coveredHosts.size)
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://xxxclub.to/torrents/details/480815"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://xxxclub.to/video/example"))
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
        assertFalse(VideoSiteProfiles.isVideoPage("https://3dhentaix.com/inflation/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://3dhentaix.com/explore/categories/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://mp4hentai.com/2026/11/si-give-me-more-segs/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://mp4hentai.com/2026/07/upcoming-october-2026-hentai-release/"))
    }
    @Test fun hentaiHavenNestedEpisodeRemainsRecognized(){
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://hentaihaven.xxx/watch/example-series/episode-4/"))
    }
    @Test fun archivebateUploaderWatchIsVideoButUploaderProfileIsNot(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://archivebate.com/u/watch/16458051"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://archivebate.com/u/profile/test-account"))
    }
    @Test fun archivebateLegalAndCategoryPagesAreNotVideo(){
        assertFalse(VideoSiteProfiles.isVideoPage("https://archivebate.com/2257"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://archivebate.com/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://archivebate.com/platform/eW91dHViZQ=="))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://archivebate.com/gender/ZmVtYWxl"))
    }
    @Test fun approvedPorneecAndChineseTaiAVOnlyAcceptSelectedMovies(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://porneec.com/sexmex-andrea-castro-my-nympho-wife/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://porneec.com/"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://porneec.com/category/lesbian/"))
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://taiav.com/cn/movie/654424d67b410d2615db16e5"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://taiav.com/cn/category/JAV_Uncensored"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://taiav.com.evil.example/cn/movie/654424d67b410d2615db16e5"))
        assertTrue(PublisherOriginalExtractors.owns(
            "https://porneec.com/sexmex-andrea-castro-my-nympho-wife/"))
        assertTrue(PublisherOriginalExtractors.owns(
            "https://taiav.com/cn/movie/654424d67b410d2615db16e5"))
    }
    @Test fun newFullVideoProvidersRejectUnverifiedPreviewAndAds(){
        val porneec="https://porneec.com/sexmex-andrea-castro-my-nympho-wife/"
        val taiav="https://taiav.com/cn/movie/654424d67b410d2615db16e5"
        val previewOnly="""<main><div class="video-player">
            <video src="https://video.example.com/trailer.mp4" data-duration="28"></video>
            </div></main>"""
        assertTrue(PublisherOriginalExtractors.fromHtml(porneec,previewOnly).isEmpty())
        assertTrue(PublisherOriginalExtractors.fromHtml(taiav,previewOnly).isEmpty())
        val confirmedOriginal="""<main><div class="video-player">
            <video src="https://video.example.com/selected-original.mp4"
                data-duration="3600"></video></div></main>"""
        assertEquals(1,PublisherOriginalExtractors
            .fromHtml(porneec,confirmedOriginal).size)
        assertEquals(1,PublisherOriginalExtractors
            .fromHtml(taiav,confirmedOriginal).size)
    }
    @Test fun liveCamRipsRecognizesOnlyActualRecording(){
        assertTrue(VideoSiteProfiles.isVideoPage(
            "https://livecamrips.to/video/11248256"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://livecamrips.to/model/372083/whoiswale/1"))
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://livecamrips.to/models"))
        assertEquals(listOf("https://mdzsmutpcvykb.net/e/z1j1rlr6ie71z0"),
            PublisherOriginalExtractors.fromHtml(
                "https://livecamrips.to/video/11248256",
                """<main><iframe id="iframe-video"
                src="https://mdzsmutpcvykb.net/e/z1j1rlr6ie71z0"
                ></iframe><iframe src="https://ads.example/e/bad111"></iframe></main>"""
            ).map{it.url})
    }
    @Test fun pornBuzSceneRequiresExactWatchAndSelectedServer(){
        val page="https://pornbuz.com/video/milfty-deliah-grace-you-think-youll-be-able-to-resist-me-cute-the-hunt-is-on-91071/"
        assertTrue(VideoSiteProfiles.isVideoPage(page))
        assertFalse(VideoSiteProfiles.isVideoPage("https://pornbuz.com/channels/"))
        assertFalse(VideoSiteProfiles.isVideoPage("https://pornbuz.com/tag/anything"))
        assertTrue(PublisherOriginalExtractors.owns(page))
        val html="""<main>
          <a href="https://watchstreamus.com/embed/uPXbeFkW">Server 1</a>
          <a href="https://streamtape.com/v/wPr0zqgxz0iJOmz/milfty.26.09.21.deliah.grace.mp4">SERVER 2</a>
          <aside><a href="https://streamtape.com/v/abcdefg123/related.mp4">SERVER 2</a></aside>
        </main>"""
        assertEquals(listOf("https://streamtape.com/v/wPr0zqgxz0iJOmz/milfty.26.09.21.deliah.grace.mp4"),
            PublisherOriginalExtractors.fromHtml(page,html).map{it.url})
        assertTrue(PublisherOriginalExtractors.fromHtml(
            page,"""<main><a href="https://streamtape.com/v/abcdefg123/unlabelled.mp4">play</a></main>"""
        ).isEmpty())
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
