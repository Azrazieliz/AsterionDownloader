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

    @Test fun roshyUsesTheSelectedBeeteamPlayerConfiguration() {
        val page="https://roshy.tv/video/test-original-scene/"
        val embed="https://kamehaus.net/e/242urr647yem"
        val html="""<div id="main-content">
          <div class="is-single-post-main-player"><div id="beeteam368_player_123"></div></div>
          <script>jQuery('#beeteam368_player_123').beeteam368_pro_player(
            {"video_mode":"embed","video_url":"<iframe src='$embed'></iframe>"});
          jQuery('#beeteam368_player_999').beeteam368_pro_player(
            {"video_mode":"embed","video_url":"<iframe src='https://kamehaus.net/e/anotherScene'></iframe>"});</script>
          </div>"""
        assertEquals(listOf(embed),SiteSpecificOriginalSources.roshy(
            Jsoup.parse(html,page),page).map{it.url})
    }

    @Test fun fullPornerVerifiedOriginalHasNoMp4ExtensionButActualMediaBytes() {
        val selected="https://fullporner.com/watch/6af6fbcd2f7835591446cb0f"
        val player="https://fullporner.com/player/9509933/14"
        val media="https://xiaoshenke.net/vid/339123456789000"
        assertTrue(FullPornerOriginalSource.selectedPage(selected))
        assertTrue(FullPornerOriginalSource.selectedPlayer(player))
        assertTrue(FullPornerOriginalSource.verifiedMedia(selected,player,media))
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,media,true))
        assertFalse(NativeMediaDownloader.isNativeEligible(selected,media,false))
        assertFalse(FullPornerOriginalSource.verifiedMedia(
            "https://fullporner.com/","https://fullporner.com/player/9509933/14",media))
        assertFalse(FullPornerOriginalSource.verifiedMedia(selected,player,
            "https://xiaoshenke.net.evil.example/vid/339123456789000"))
        assertFalse(FullPornerOriginalSource.verifiedMedia(selected,player,
            "https://xiaoshenke.net/other/339123456789000"))
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
    @Test fun liveHelloPornSelectedVideoUsesOnlyVoeEmbedOriginal(){
        val selected="https://helloporn.com/video-28699-fakedrivingschool-naughty-student-late-for-her-exam-j"
        val player="https://voe.sx/e/wx8walm5hqkj"
        val html="""<main>
            <iframe src="https://ads.example/e/wx8walm5hqkj"></iframe>
            <iframe src="$player" allowfullscreen></iframe>
            <iframe src="https://voe.sx.evil.example/e/wx8walm5hqkj"></iframe>
            </main>"""
        val sources=SiteSpecificOriginalSources.helloPorn(
            Jsoup.parse(html,selected),selected)
        assertEquals(listOf(player),sources.map{it.url})
        assertEquals(selected,sources.single().referer)
        assertTrue(SiteSpecificOriginalSources.helloPorn(
            Jsoup.parse(html,"https://helloporn.com/videos"),
            "https://helloporn.com/videos").isEmpty())
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
            // Dynamic players can expose currentSrc before metadata supplies
            // duration; do not discard a real source merely because it is 0.
            val unmeasured=short.replace("data-duration=\"25\"",
                "data-duration=\"0\"")
            assertEquals(listOf(video),
                SiteSpecificOriginalSources.selectedStatic(
                    Jsoup.parse(unmeasured,page),page,host).map{it.url})
        }
    }
    @Test fun hentaiBrosReadsTheSelectedFlowplayerSourceInsteadOfRelatedClips(){
        val page="https://hentaibros.net/sample-episode-1/"
        val playerUrl="https://cdn.example/Anime Episode 1.mp4"
        val html="""<main><div class="video-player">
          <div class="flowplayer" data-item='{"sources":[
            {"src":"$playerUrl","type":"video/mp4"},
            {"src":"https://cdn.example/preview.mp4","type":"video/mp4"}]}'></div>
          <aside><div class="flowplayer" data-item='{"sources":[
            {"src":"https://cdn.example/another-item.mp4","type":"video/mp4"}]}'></div></aside>
          </div></main>"""
        val selected=SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,page),page,"hentaibros.net")
        assertEquals(listOf(playerUrl.replace(" ","%20")),selected.map{it.url})
        assertEquals(page,selected.single().referer)
    }
    @Test fun threeDHentaiRefusesPlayableUnrelatedAndAmbiguousVideos(){
        val selected="https://3dhentaix.com/actual-scene-episode/"
        val original="https://cdn.example/actual-scene.mp4"
        val wrong="https://cdn.example/other-scene.mp4"
        val sourceHtml="""<main>
            <div class="video-player"><video src="$original" data-duration="1234"></video></div>
            <aside class="recommended-videos">
              <div class="video-player"><video src="$wrong" data-duration="1300"></video></div>
            </aside>
            </main>"""
        assertEquals(listOf(original),SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(sourceHtml,selected),selected,"3dhentaix.com").map{it.url})
        val wrongOnly="""<main><section class="related">
           <div class="video-player"><video src="$wrong" data-duration="2000"></video></div>
           </section></main>"""
        assertTrue(SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(wrongOnly,selected),selected,"3dhentaix.com").isEmpty())
        val ambiguous="""<main>
           <div class="video-player"><video src="$original" data-duration="1234"></video></div>
           <div class="video-player"><video src="$wrong" data-duration="1300"></video></div>
           </main>"""
        assertTrue(SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(ambiguous,selected),selected,"3dhentaix.com").isEmpty())
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
