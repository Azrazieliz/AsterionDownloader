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

    @Test fun realHentaibrosFlowplayerEscapedJsonProducesExactNativeOriginal(){
        val selected="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
        val html="""<article class="entry-content"><div class="video-player">
          <div class="responsive-player">
           <div id="wpfp_3235"
             data-item="{&quot;sources&quot;:[{&quot;src&quot;:&quot;https:\/\/povblowjob.net\/Deco x Deco The Animation 01 Fixed Sub.mp4&quot;,&quot;type&quot;:&quot;video\/mp4&quot;}],&quot;id&quot;:&quot;3241&quot;}"
             class="freedomplayer flowplayer no-brand is-splash is-paused"></div>
          </div></div></article>"""
        val result=SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,selected),selected,"hentaibros.net")
        assertEquals(1,result.size)
        val expected="https://povblowjob.net/Deco%20x%20Deco%20The%20Animation%2001%20Fixed%20Sub.mp4"
        assertEquals(expected,result.single().url)
        assertEquals(selected,result.single().referer)
        assertTrue(NativeMediaDownloader.isNativeEligible(selected,expected))
    }

    @Test fun hentaBrosActualWordpressWpfpElementDoesNotRequireFlowplayerClass(){
        val selected="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
        val html="""<article class="post"><header class="entry-header">
          <div class="video-player"><div class="responsive-player">
            <div id="wpfp_3235" data-item='{"sources":[
              {"src":"https://cdn.example/Selected Episode 01.mp4","type":"video/mp4"}]}'>
            </div></div></div></header>
          <aside class="related"><div class="video-player">
            <div id="wpfp_9999" data-item='{"sources":[
              {"src":"https://cdn.example/unrelated.mp4"}]}'></div>
          </div></aside></article>"""
        val sources=SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,selected),selected,"hentaibros.net")
        assertEquals(listOf("https://cdn.example/Selected%20Episode%2001.mp4"),
            sources.map{it.url})
        assertEquals(selected,sources.single().referer)
    }

    @Test fun hentaBrosRejectsAmbiguousSelectedPlayers(){
        val selected="https://hentaibros.net/episode-1/"
        val html="""<main><div class="video-player">
            <div id="wpfp_1" data-item='{"sources":[{"src":"https://cdn.example/one.mp4"}]}'></div>
            <div id="wpfp_2" data-item='{"sources":[{"src":"https://cdn.example/two.mp4"}]}'></div>
        </div></main>"""
        assertTrue(SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,selected),selected,"hentaibros.net").isEmpty())
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

    @Test fun roshyIframeWebpageIsNotAPlayableOriginal(){
        val publisher="https://roshy.tv/video/test-original-scene/"
        val player="https://kamehaus.net/e/242urr647yem"
        val file="https://nitroflare.com/view/AAABBBCCC/scene.mp4"
        val media="https://cdn.media.example.net/selected/master.m3u8"
        val initial=listOf(
            SiteSpecificOriginalSources.Source(player,publisher),
            SiteSpecificOriginalSources.Source(file,publisher))
        val pending=SiteSpecificOriginalSources.roshyTransferableCandidates(
            initial,emptyList())
        assertEquals(listOf(file),pending.map{it.url})
        val resolved=SiteSpecificOriginalSources.roshyTransferableCandidates(
            initial,listOf(SiteSpecificOriginalSources.Source(media,player)))
        assertEquals(listOf(media,file),resolved.map{it.url})
        assertFalse(resolved.any{it.url==player})
    }

    @Test fun roshyRefusesUnattributedKamehausPlayers(){
        val selected="https://roshy.tv/video/test-original-scene/"
        val html="""<div id="main-content"><div class="is-single-post-main-player">
            <div id="beeteam368_player_345"></div></div>
            <script>jQuery('#beeteam368_player_123').beeteam368_pro_player(
            {"video_mode":"embed","video_url":"<iframe src='https://kamehaus.net/e/otherScene'></iframe>"});
            </script></div>"""
        assertTrue(SiteSpecificOriginalSources.roshy(
            Jsoup.parse(html,selected),selected).isEmpty())
    }

    @Test fun fullPornerSelectedPlayerFindsExtensionlessOriginalButNotRecommendation(){
        val selected="https://fullporner.com/watch/6af84d762f7835591446cc91"
        val player="https://fullporner.com/player/9509933/14"
        val exact="https://xiaoshenke.net/vid/339123456789000"
        val html="""<video id="flvv"><source type="video/mp4" src="$exact"></video>
             <aside class="related"><video><source type="video/mp4"
               src="https://xiaoshenke.net/vid/other567890"></video></aside>"""
        assertEquals(listOf(exact),SiteSpecificOriginalSources.fullPornerSelectedPlayer(
            Jsoup.parse(html,player),selected,player).map{it.url})
        assertTrue(SiteSpecificOriginalSources.fullPornerSelectedPlayer(
            Jsoup.parse(html,player),"https://fullporner.com/",player).isEmpty())
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
    @Test fun selectedHosterFallbackAdmitsExactPlayersButNeverAdIframes(){
        val jav="https://javideo.net/en/mida-863-mosaic"
        val voe="https://helloporn.com/video-28699-selected-video"
        val stream="https://streamtape.com/e/AaBbCcDdEe12345"
        val player="https://voe.sx/e/wx8walm5hqkj"
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(jav,stream))
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(voe,player))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            jav,"https://streamtape.com/e/../ads"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            jav,"https://streamtape.com.evil.example/e/AaBbCcDdEe12345"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            voe,"https://ads.example/e/wx8walm5hqkj"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            "https://rule34video.com/video/4727779/",stream))
    }

    @Test fun publishedRoshyDownloadPageVariantsRemainExtractorCandidates(){
        val page="https://roshy.tv/video/test-original-scene/"
        val variants=listOf(
            "https://k2s.cc/download/aabbcc",
            "https://nitroflare.com/download/AAABBBCC",
            "https://uploadgig.com/file/abcdef",
            "https://uploadgig.com/download/abcdef")
        val sources=SiteSpecificOriginalSources.roshyTransferableCandidates(
            variants.map{SiteSpecificOriginalSources.Source(it,page)},emptyList())
        assertEquals(variants,sources.map{it.url})
        assertTrue(sources.all{
            !NativeMediaDownloader.isNativeEligible(page,it.url)})
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
    @Test fun selectedPlayerFallbacksNeverConsumeUnrelatedFrameOrQuery(){
        val nh="https://uncensoredhentai.xxx/watch/episode-3/"
        val voe="https://helloporn.com/video-28699-selected-clip"
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            nh,"https://nhplayer.com/v/Z2flAXXPucyCbVJ/"))
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            voe,"https://voe.sx/e/wx8walm5hqkj"))
        for(bad in listOf(
            "https://nhplayer.com/v/Z2flAXXPucyCbVJ/?advert=1",
            "https://nhplayer.com.evil.example/v/Z2flAXXPucyCbVJ/",
            "http://nhplayer.com/v/Z2flAXXPucyCbVJ/",
            "https://nhplayer.com/watch/Z2flAXXPucyCbVJ/",
            "https://nhplayer.com/v/../../ads"
        ))assertFalse(bad,SiteSpecificOriginalSources.selectedExtractorFrame(nh,bad))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            nh,"https://voe.sx/e/wx8walm5hqkj"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            "https://rule34video.com/video/4727779/",
            "https://nhplayer.com/v/Z2flAXXPucyCbVJ/"))
    }
    @Test fun selectedNhplayerServerMustBeUniquelyPublishedByThatIframe(){
        val selected="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
        val accepted="https://nhplayer.com/player.php?data=selected_opaque_token"
        val html="""<div class="servers"><ul>
            <li data-id="/player.php?data=selected_opaque_token">Server 1</li>
            </ul></div><aside class="ads"><iframe src="https://ads.example/video.mp4">
            </iframe></aside>"""
        assertEquals(accepted,
            SiteSpecificOriginalSources.selectedNhPlayerServer(selected,html))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            "https://nhplayer.com/v/OTHER999/?advert=1",html))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            selected,html.replace("nhplayer.com/player.php",
                "ads.example/player.php").replace(
                "/player.php?data=selected_opaque_token",
                "https://ads.example/player.php?data=selected_opaque_token")))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            selected,html.replace("</ul>",
                "<li data-id='/player.php?data=different'>Server 2</li></ul>")))
        assertNull(SiteSpecificOriginalSources.selectedNhPlayerServer(
            selected,"<div class='servers'><ul><li data-id='/watch/other'>"+
                "Recommended</li></ul></div>"))
    }
    @Test fun firstPartyNhPlayerAjaxAcceptsOnlyExactSignedSelectedOriginal(){
        val iframe="https://nhplayer.com/v/Z2flAXXPucyCbVJ/"
        val server="https://nhplayer.com/player.php?i=token1&s=2&vid=scene3"
        val original="https://r2.1hanime.com/selected/episode-3.mp4?verify=opaque-token"
        assertTrue(SiteSpecificOriginalSources.selectedNhPlayerAjaxOriginal(
            iframe,server,original))
        for(bad in listOf(
            "https://ads.example.com/selected/episode-3.mp4?verify=opaque-token",
            "https://r2.1hanime.com.evil.example/selected/episode-3.mp4?verify=opaque-token",
            "https://r2.1hanime.com/selected/trailer.mp4?verify=opaque-token",
            "https://r2.1hanime.com/selected/episode-3.mp4",
            "https://r2.1hanime.com/selected/episode-3.mp4?verify=opaque-token&ad=1",
            "http://r2.1hanime.com/selected/episode-3.mp4?verify=opaque-token"
        )) assertFalse(bad,SiteSpecificOriginalSources.selectedNhPlayerAjaxOriginal(
            iframe,server,bad))
        assertFalse(SiteSpecificOriginalSources.selectedNhPlayerAjaxOriginal(
            iframe,"https://nhplayer.com/player.php?i=token1&vid=scene3",original))
        assertFalse(SiteSpecificOriginalSources.selectedNhPlayerAjaxOriginal(
            "https://nhplayer.com/v/Z2flAXXPucyCbVJ/?ad=1",server,original))
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
        // JAVIDEO's public page contains an autoplay advertisement video.
        // Only the actual selected server iframe is attributable.
        assertTrue(SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse("""<article><video src="$video"></video></article>""",jav),
            jav,"javideo.net").isEmpty())
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

    @Test fun helloPornOnlyAdmitsOneSelectedVoeIframe(){
        val page="https://helloporn.com/video-12345-selected-episode"
        val selected="https://voe.sx/e/aBbCcDd123"
        val html="""<main><div id="player"><iframe src="$selected"></iframe></div>
            <aside><iframe src="https://voe.sx/e/unrelated999"></iframe></aside>
            </main>"""
        assertEquals(listOf(selected),SiteSpecificOriginalSources.helloPorn(
            Jsoup.parse(html,page),page).map{it.url})
        val ambiguous="""<main><div id="player">
            <iframe src="$selected"></iframe>
            <iframe src="https://voe.sx/e/Another999"></iframe>
            </div></main>"""
        assertTrue(SiteSpecificOriginalSources.helloPorn(
            Jsoup.parse(ambiguous,page),page).isEmpty())
    }
    @Test fun javideoRealMidaRouteAcceptsOnlySelectedStreamTapeIframe(){
        val selected="https://javideo.net/en/mida-863-mosaic"
        val player="https://streamtape.com/e/AaBbCcDdEe12345"
        assertTrue(VideoSiteProfiles.isVideoPage(selected))
        val page="""<main>
          <div class="video-player"><iframe src="$player"></iframe></div>
          <div class="related"><video src="https://n2j9y0x0.bxcdn.net/ad.mp4"></video></div>
          </main>"""
        assertEquals(listOf(player),SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(page,selected),selected,"javideo.net").map{it.url})
        assertTrue(SiteSpecificOriginalSources.javideoSelected(
            Jsoup.parse("""<main><div class="video-player">
              <video src="https://n2j9y0x0.bxcdn.net/ad.mp4"></video>
            </div></main>""",selected),selected).isEmpty())
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
    @Test fun hentaBrosDoesNotFallbackToSameWrapperUnrelatedVideo(){
        val selected="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
        val html="""<main><div class="video-player">
            <video src="https://cdn.example/secondary.mp4"
                data-duration="1200" controls></video>
            <div id="wpfp_3235" data-item='{"sources":[
                {"src":"https://cdn.example/actual-original.mp4",
                 "type":"video/mp4"}]}'></div>
        </div></main>"""
        val candidates=SiteSpecificOriginalSources.selectedStatic(
            Jsoup.parse(html,selected),selected,"hentaibros.net")
        assertEquals(listOf("https://cdn.example/actual-original.mp4"),
            candidates.map{it.url})
        assertEquals(selected,candidates.single().referer)
    }



    @Test fun mp4HentaiSelectedRapidgatorFilenamePunctuationIsNotDropped() {
        val clicked="https://www.mirrored.to/getlink?hid=selectedMirror01&tid=selectedHost02"
        val published="https://rapidgator.net/file/12345678901234567890123456789012/" +
            "Example (Full) [2026].mp4.html"
        val normalized=Mp4HentaiPublishedHost.publishedHref(clicked,published)
        assertNotNull(normalized)
        assertTrue(normalized!!.contains("%5B2026%5D"))
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorHandoff(clicked,normalized))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorHandoff(
            "https://unverified.example/getlink?hid=selectedMirror01&tid=selectedHost02",
            normalized))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorHandoff(clicked,
            "https://rapidgator.net.evil.example/file/12345678901234567890123456789012/Example.mp4.html"))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorHandoff(clicked,
            "https://rapidgator.net/file/12345678901234567890123456789012/Example%2Fother.mp4.html"))
        assertFalse(Mp4HentaiPublishedHost.selectedMirrorHandoff(clicked,
            "https://rapidgator.net/file/12345678901234567890123456789012/Example.mp4.html?ad=1"))
    }

    @Test fun mp4HentaiSelectedMirrorUsesAllOfficialSameFileHostersOnly() {
        val mirror="https://www.mirrored.to/files/DF7298C6/"
        val first="https://www.mirrored.to/getlink?hid=one01&tid=host01"
        val second="https://www.mirrored.to/getlink?hid=two02&tid=host02"
        val html="""
          <article>
            <a href="/getlink?hid=one01&tid=host01">Freedl file host</a>
            <a href="/getlink?hid=two02&tid=host02">Rapidgator file host</a>
            <a href="/getlink?hid=one01&tid=host01">Duplicate</a>
            <aside class="ads"><a href="/getlink?hid=ad123&tid=ad234">Ad</a></aside>
            <a href="https://evil.example/getlink?hid=one01&tid=host01">Untrusted host</a>
            <a href="/getlink?hid=one01&tid=host01&extra=ad">Untrusted query</a>
            <a href="https://rapidgator.net/file/aaaaaaaaaa/Other.mp4.html">
              Unattributed direct link
            </a>
          </article>
        """.trimIndent()
        assertEquals(listOf(first,second),
            Mp4HentaiPublishedHost.selectedMirrorDownloadRoutes(
                Jsoup.parse(html,mirror),mirror))
        assertTrue(Mp4HentaiPublishedHost.selectedMirrorDownloadRoutes(
            Jsoup.parse(html,mirror),
            "https://www.mirrored.to/files/WRONG_ID/").isEmpty())
    }
}
