package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression contracts for the user's failed v1.3.60 provider acceptance.
 * Pure offline provider HTML fixtures are NOT live download verification.
 */
class ReportedSiteSpecificOriginalsTest {
    private val video="https://cdn.publisher.test/full-original-1080p.mp4"

    @Test fun mp4HentaiExtractsVideoOnlyNeverSelectedHtmlOrArticleLinks() {
        val page="https://mp4hentai.com/2026/11/sample-animation-episode/"
        val html="""<article>
          <div class="entry-content">
          <a href="https://mp4hentai.com/2026/11/sample-animation-episode/">Read article</a>
          <a href="https://cdn.publisher.test/other-scene.mp4">Other recommendations</a>
          <video src="$video" controls></video></div></article>"""
        val sources=SiteSpecificOriginalSources.mp4hentai(
            Jsoup.parse(html,page),page)
        assertEquals(listOf(video),sources.map{it.url})
        assertTrue(SiteSpecificOriginalSources.mp4hentai(
            Jsoup.parse(html,"https://mp4hentai.com/"),"https://mp4hentai.com/")
            .isEmpty())
        assertFalse(SiteSpecificOriginalSources.isHosterPage(page))
    }

    @Test fun roshyPublishesNamedOriginalFileHostsNotNativeVideoFiles(){
        val page="https://roshy.tv/video/test-original-scene/"
        val k2s="https://k2s.cc/file/aabbccdd/scene.mp4"
        val nitro="https://nitroflare.com/view/AAABBBCCC/scene.mp4"
        val file="https://uploadgig.com/file/download/ABCDEF/scene.mp4"
        val html="""<article class="entry-content">
          <a href="$k2s">K2S original</a>
          <a href="$nitro">Nitro original</a>
          <a href="$file">Uploadgig original</a>
          <a href="https://ads.example/file/AA">Ad</a>
          <a href="/video/other-scene/">Recommendation</a></article>"""
        val sources=SiteSpecificOriginalSources.roshy(Jsoup.parse(html,page),page)
        assertEquals(listOf(k2s,nitro,file),sources.map{it.url})
        for(source in sources){
            assertTrue(SiteSpecificOriginalSources.isHosterPage(source.url))
            assertFalse(NativeMediaDownloader.isNativeEligible(page,source.url))
        }
    }

    @Test fun exactFullPornerPlayerRejectsOtherIframes(){
        val page="https://fullporner.com/watch/6af6fbcd2f7835591446cb0f"
        val selected="https://fullporner.com/player/9509933/14"
        val html="""<iframe src="https://ads.example/banner/9509933"></iframe>
          <iframe src="/player/9509933/14"></iframe>
          <iframe src="https://fullporner.com.evil.example/player/9509933/14"></iframe>"""
        assertEquals(selected,SiteSpecificOriginalSources.playerLink(
            Jsoup.parse(html,page),"fullporner.com"))
    }
    @Test fun hqPornerAcceptsOnlyItsActualPublisherPlayer(){
        val page="https://hqpornerxxx.com/videos/sample-title-39904.html"
        val player="https://myxstudio.top/embed/5d8297e451a0e650ca"
        val html="""<div id="player">
          <iframe src="https://ads.example/embed/5d8297e451a0e650ca"></iframe>
          <iframe src="$player" allowfullscreen></iframe>
          </div>"""
        assertEquals(player,SiteSpecificOriginalSources.playerLink(
            Jsoup.parse(html,page),"hqpornerxxx.com"))
    }
    @Test fun uncensoredHentaiResolvesOnlyItsExactNhplayerIframe(){
        val page="https://uncensoredhentai.xxx/watch/episode-3/"
        val player="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
        val html="""<div class="video-player">
          <iframe src="https://ads.example/v/Z2flAXXPucyCbVJ/"></iframe>
          <iframe src="$player" allowfullscreen></iframe>
          </div>"""
        assertEquals(player,SiteSpecificOriginalSources.playerLink(
            Jsoup.parse(html,page),"uncensoredhentai.xxx"))
    }
    @Test fun helloPornAndJavideoUseSelectedVideoPlayerNotPreview(){
        val hello="https://helloporn.com/video/selected-title"
        val jav="https://javideo.net/en/fc2ppv-4994603"
        assertEquals(listOf(video),SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse("""<div class="video-player">
              <source src="$video"></source>
              <aside><source src="https://ads.example/ad.mp4"></source></aside>
              </div>""",hello),hello,"helloporn.com").map{it.url})
        assertEquals(listOf(video),SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse("""<article><video src="$video"></video></article>""",jav),
            jav,"javideo.net").map{it.url})
    }
    @Test fun hentaiBrosAnd3dHentaiRejectClipsWithoutFullDuration(){
        for(pair in listOf(
            "https://hentaibros.net/sample-episode-1/" to "hentaibros.net",
            "https://3dhentaix.com/animated-scene/" to "3dhentaix.com")){
            val (page,host)=pair
            val short="""<main><div class="video-player">
              <video src="$video" data-duration="25"></video></div></main>"""
            val full=short.replace("data-duration=\"25\"","data-duration=\"1350\"")
            assertTrue(SiteSpecificOriginalSources.selectedStatic(
                Jsoup.parse(short,page),page,host).isEmpty())
            assertEquals(listOf(video),
                SiteSpecificOriginalSources.selectedStatic(
                    Jsoup.parse(full,page),page,host).map{it.url})
        }
    }
    @Test fun onlyHentaiWorkingPathRemainsOutsideNewResolver(){
        assertFalse(SiteSpecificOriginalSources.owns(
            "https://onlyhentaistuff.com/video/selected"))
        assertTrue(PublisherOriginalExtractors.owns(
            "https://onlyhentaistuff.com/video/selected"))
    }
    @Test fun torrentPublisherCannotClaimToBeAVideoProvider(){
        assertFalse(VideoSiteProfiles.isVideoPage(
            "https://xxxclub.to/torrents/details/480815"))
        assertFalse(PublisherOriginalExtractors.owns(
            "https://xxxclub.to/torrents/details/480815"))
    }
}
