package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class PublisherOriginalExtractorsTest {
    private val cases=mapOf(
        "pornhub.com" to "https://pornhub.com/view_video.php?viewkey=ph123456",
        "eporner.com" to "https://www.eporner.com/video-MSADu4C0K92/",
        "hqpornerxxx.com" to "https://hqpornerxxx.com/video/example",
        "jav.guru" to "https://jav.guru/1076295/scene-663-title/",
        "javideo.net" to "https://javideo.net/en/fc2ppv-4994603",
        "roshy.tv" to "https://roshy.tv/video/example",
        "cums.net" to "https://cums.net/video/example",
        "watchporn.to" to "https://watchporn.to/video/1782/title",
        "archivebate.com" to "https://archivebate.com/watch/16458050",
        "livecamrips.to" to "https://livecamrips.to/video/11248256",
        "xxxi.porn" to "https://xxxi.porn/video/example",
        "notfans.com" to "https://notfans.com/video/example",
        "fullporner.com" to "https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",

        "freepornvideos.xxx" to "https://www.freepornvideos.xxx/videos/example",
        "porneec.com" to "https://porneec.com/sexmex-andrea-castro-my-nympho-wife/",
        "pornbuz.com" to "https://pornbuz.com/video/milfty-deliah-grace-you-think-youll-be-able-to-resist-me-cute-the-hunt-is-on-91071/",
        "taiav.com" to "https://taiav.com/cn/movie/654424d67b410d2615db16e5",
        "omg.porn" to "https://omg.porn/video/example",
        "hentaibros.net" to "https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
        "onlyhentaistuff.com" to "https://onlyhentaistuff.com/video/example",
        "uncensoredhentai.xxx" to "https://uncensoredhentai.xxx/watch/episode-3/",
        "3dhentaix.com" to "https://3dhentaix.com/ntr-compilation/",
        "rule34video.com" to "https://rule34video.com/video/4727221/",
        "avple.tv" to "https://avple.tv/video/sample-123",
        "pimpbunny.com" to "https://pimpbunny.com/videos/selected-scene-123",
        "xchina.co" to "https://en.xchina.co/video/6b00b2b6032b1",
        "bubblecum.lol" to "https://www.bubblecum.lol/video/selected-scene-title/",
        "pornavhd.com" to "https://pornavhd.com/2026/11/14/selected_20261114/",
        "cosplaytele.com" to "https://cosplaytele.com/selected-cosplay-set/"
    )

    @Test fun eachExistingVideoPublisherHasItsOwnNamedParser(){
        assertEquals(VideoSiteProfiles.coveredHosts,
            PublisherOriginalExtractors.coveredHosts)
        assertEquals(cases.keys,PublisherOriginalExtractors.coveredHosts)
        assertFalse(VideoSiteProfiles.isVideoPage("https://camstreams.tv/videos/1363858/selected-cam-recording/"))
        assertFalse(PublisherOriginalExtractors.owns("https://camstreams.tv/videos/1363858/selected-cam-recording/"))
        for((host,selected) in cases) {
            assertTrue("Route unexpectedly unrecognized: $host",
                VideoSiteProfiles.isVideoPage(selected))
            assertTrue("No native provider adapter: $host",
                PublisherOriginalExtractors.owns(selected))
        }
    }
    @Test fun directMediaIsSelectedFromSitePlayerNotRecommendationOrAdvertisement(){
        val html="""<html><main>
          <video src="https://ads.example/preview.mp4"></video>
          <div id="player"><video src="https://cdn.example/original.mp4"></video></div>
          <aside><video src="https://cdn.example/unrelated.mp4"></video></aside>
          </main></html>"""
        val result=PublisherOriginalExtractors.fromHtml(
            cases.getValue("watchporn.to"),html)
        assertEquals(listOf("https://cdn.example/original.mp4"),
            result.map{it.url})
    }
    @Test fun realSelectedPublisherMetadataMayCarryFullOriginal(){
        val html="""<script type="application/ld+json">
          {"@context":"https://schema.org","@type":"VideoObject",
           "name":"selected","contentUrl":"https://cdn.example/selected.mp4"}
        </script>"""
        assertEquals(listOf("https://cdn.example/selected.mp4"),
            PublisherOriginalExtractors.fromHtml(
                cases.getValue("rule34video.com"),html).map{it.url})
    }
    @Test fun knownPublisherEmbedHtmlCanYieldOriginalWithoutWatchPageGateway(){
        val selected=cases.getValue("eporner.com")
        val publicEmbed="""<main><section id="player"><video>
            <source src="https://cdn.example/eporner-full-scene.mp4"
                    type="video/mp4"/>
          </video></section>
          <aside class="related"><video>
            <source src="https://cdn.example/unrelated-preview.mp4"/>
          </video></aside></main>"""
        val result=PublisherOriginalExtractors.fromHtml(selected,publicEmbed)
        assertEquals(listOf("https://cdn.example/eporner-full-scene.mp4"),
            result.map{it.url})
        assertEquals("https://www.eporner.com/embed/MSADu4C0K92/",
            EpornerOriginalSource.officialEmbed(selected))
    }

    @Test fun archivebateIsExactWatchEmbedNotPreviewOrAdvert(){
        val html="""<div class="player">
          <iframe src="https://ads.example/e/ABC12345"></iframe>
          <iframe src="https://mixdrop.ag/e/ABcdEF123"></iframe>
          </div>
          <video src="https://cdn.freefile.io/other-preview.mp4"></video>"""
        assertEquals(listOf("https://mixdrop.ag/e/ABcdEF123"),
            PublisherOriginalExtractors.fromHtml(
                cases.getValue("archivebate.com"),html).map{it.url})
    }
    @Test fun liveCamRipsUsesOnlyObservedExactSelectedVidaraFrame(){
        val selected=cases.getValue("livecamrips.to")
        val html="""<main><section class="video-player">
          <iframe id="iframe-video"
            src="https://vidara.to/KJq6mx17bB2/" ></iframe>
          <iframe src="https://creative.whitetrafsa.com/widgets/v4/Universal"></iframe>
          <aside class="related"><iframe id="iframe-video"
            src="https://vidara.to/AnotherId12345"></iframe></aside>
          </section></main>"""
        val frames=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(listOf("https://vidara.to/KJq6mx17bB2/"),
            frames.map{it.url})
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,frames.single().url))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://vidara.to/watch/OtherId12345"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://vidara.to/KJq6mx17bB2/?preview=1"))
    }

    @Test fun uncensoredExactNhplayerRejectsOtherExternalIframes(){
        val html="""<iframe src="https://nhplayer.com/v/ABCDE12345/"></iframe>
          <iframe src="https://commenthub.io/v/ABCDE12345/"></iframe>"""
        assertEquals(listOf("https://nhplayer.com/v/ABCDE12345/"),
            PublisherOriginalExtractors.fromHtml(
                cases.getValue("uncensoredhentai.xxx"),html).map{it.url})
    }
    @Test fun shortOnlyHentaibrosAnd3dHentaixMediaCannotBePromoted(){
        for(host in listOf("hentaibros.net","3dhentaix.com")){
            val page=cases.getValue(host)
            val short="""<main><div class="video-player">
              <video data-duration="19" src="https://cdn.example/short.mp4"></video>
              </div></main>"""
            assertTrue(PublisherOriginalExtractors.fromHtml(page,short).isEmpty())
            val full=short.replace("data-duration=\"19\"","data-duration=\"1800\"")
                .replace("/short.mp4","/full.mp4")
            assertEquals(listOf("https://cdn.example/full.mp4"),
                PublisherOriginalExtractors.fromHtml(page,full).map{it.url})
        }
    }
    @Test fun exactSelectedPlayerHlsManifestIsDistinctFromPreview(){
        val selected=cases.getValue("watchporn.to")
        val html="""<main>
            <video src="https://cdn.example/preview.mp4"></video>
            <div id="player">
              <source src="https://cdn.example/selected/master.m3u8"
                      type="application/vnd.apple.mpegurl"/>
            </div></main>"""
        assertEquals(listOf("https://cdn.example/selected/master.m3u8"),
            PublisherOriginalExtractors.fromHtml(selected,html).map{it.url})
    }
    @Test fun cosplayTeleFallbackUsesExactArticleVideoNotImageOrRelatedVideo(){
        val selected=cases.getValue("cosplaytele.com")
        val html="""<main>
          <article><div class="entry-content">
          <img src="https://cdn.cosplaytele.com/selected.jpg"/>
          <video src="https://cdn.cosplaytele.com/selected-full.mp4"></video>
          </div></article>
          <aside class="recommended">
          <video src="https://cdn.cosplaytele.com/unrelated.mp4"></video>
          </aside></main>"""
        assertEquals(listOf("https://cdn.cosplaytele.com/selected-full.mp4"),
            PublisherOriginalExtractors.fromHtml(selected,html).map{it.url})
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,
            "<main><img src='https://cdn.cosplaytele.com/photo.jpg'/></main>").isEmpty())
    }
    @Test fun xchinaTakesOnlyMatchingSelectedFirstPartyHls(){
        val selected="https://en.xchina.co/video/6b0186908cd75"
        val html="""<main><article><div
          id="video-player" class="h-full w-full"
          data-src="/hls/6b0186908cd75/master.m3u8"></div>
          <aside class="related"><div
            id="video-player" data-src="/hls/ffffffffffffff/master.m3u8"></div>
          </aside></article></main>"""
        assertEquals(listOf(
            "https://en.xchina.co/hls/6b0186908cd75/master.m3u8"),
            PublisherOriginalExtractors.fromHtml(selected,html).map{it.url})
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,
            html.replace("/hls/6b0186908cd75/master.m3u8",
                "/hls/ffffffffffffff/master.m3u8")).isEmpty())
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,
            html.replace("data-src=\"/hls/6b0186908cd75/master.m3u8\"",
                "data-src=\"https://unrelated.invalid/hls/6b0186908cd75/master.m3u8\"")).isEmpty())
    }

    @Test fun pornavhdUsesOnlyObservedSelectedPlayerrecordIframe(){
        val selected=cases.getValue("pornavhd.com")
        val html="""<main><article>
            <iframe src="https://video.playerrecord.cfd/e/tj9qby7osr0y"></iframe>
            <div class="related"><iframe
              src="https://video.playerrecord.cfd/e/another123456"></iframe></div>
            <aside class="ads"><iframe
              src="https://creative.whitetrafsa.com/widgets/v4/Universal"></iframe></aside>
            </article></main>"""
        val frames=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(listOf("https://video.playerrecord.cfd/e/tj9qby7osr0y"),
            frames.map{it.url})
        assertTrue(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,frames.single().url))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://creative.whitetrafsa.com/e/tj9qby7osr0y"))
        assertFalse(SiteSpecificOriginalSources.selectedExtractorFrame(
            selected,"https://video.playerrecord.cfd/e/tj9qby7osr0y?ad=1"))
    }

    @Test fun listingHomepagesAndUnknownPublishersNeverCreateOriginals(){
        val html="""<div id="player"><video src="https://cdn.example/original.mp4"></video></div>"""
        assertTrue(PublisherOriginalExtractors.fromHtml(
            "https://helloporn.com/video-12345-selected/",html).isEmpty())
        assertFalse(PublisherOriginalExtractors.owns(
            "https://helloporn.com/video-12345-selected/"))
        assertTrue(PublisherOriginalExtractors.fromHtml(
            "https://unknown.example/video/123",html).isEmpty())
    }

    @Test fun relatedLongVideoIsNeverSelectedEvenWithValidDuration(){
        val selected=cases.getValue("hentaibros.net")
        val html="""<main>
            <div class="video-player">
              <video data-duration="2100"
                src="https://cdn.example/selected-original.mp4"></video>
            </div>
            <aside class="related">
              <div class="video-player">
                <video data-duration="2100"
                  src="https://cdn.example/another-scene.mp4"></video>
              </div>
            </aside>
            <div class="recommended">
              <div class="video-player">
                <video data-duration="2250"
                  src="https://cdn.example/recommended.mp4"></video>
              </div>
            </div>
        </main>"""
        assertEquals(listOf("https://cdn.example/selected-original.mp4"),
            PublisherOriginalExtractors.fromHtml(selected,html).map{it.url})
    }

}
