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
        "xxxi.porn" to "https://xxxi.porn/video/example",
        "helloporn.com" to "https://helloporn.com/video/example",
        "notfans.com" to "https://notfans.com/video/example",
        "fullporner.com" to "https://fullporner.com/watch/6af6fbcd2f7835591446cb0f",
        "xxxclub.to" to "https://xxxclub.to/video/example",
        "freepornvideos.xxx" to "https://www.freepornvideos.xxx/videos/example",
        "omg.porn" to "https://omg.porn/video/example",
        "hentaibros.net" to "https://hentaibros.net/deco-x-deco-the-animation-episode-1/",
        "mp4hentai.com" to "https://mp4hentai.com/2026/11/example-episode/",
        "onlyhentaistuff.com" to "https://onlyhentaistuff.com/video/example",
        "uncensoredhentai.xxx" to "https://uncensoredhentai.xxx/watch/episode-3/",
        "3dhentaix.com" to "https://3dhentaix.com/ntr-compilation/",
        "hentaihaven.xxx" to "https://hentaihaven.xxx/watch/example",
        "rule34video.com" to "https://rule34video.com/video/4727221/",
        "camstreams.tv" to "https://camstreams.tv/video/example"
    )

    @Test fun eachExistingVideoPublisherHasItsOwnNamedParser(){
        assertEquals(VideoSiteProfiles.coveredHosts,
            PublisherOriginalExtractors.coveredHosts)
        assertEquals(cases.keys,PublisherOriginalExtractors.coveredHosts)
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
            cases.getValue("helloporn.com"),html)
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
        val selected=cases.getValue("helloporn.com")
        val html="""<main>
            <video src="https://cdn.example/preview.mp4"></video>
            <div id="player">
              <source src="https://cdn.example/selected/master.m3u8"
                      type="application/vnd.apple.mpegurl"/>
            </div></main>"""
        assertEquals(listOf("https://cdn.example/selected/master.m3u8"),
            PublisherOriginalExtractors.fromHtml(selected,html).map{it.url})
    }
    @Test fun listingHomepagesAndUnknownPublishersNeverCreateOriginals(){
        val html="""<div id="player"><video src="https://cdn.example/original.mp4"></video></div>"""
        assertTrue(PublisherOriginalExtractors.fromHtml(
            "https://helloporn.com/",html).isEmpty())
        assertTrue(PublisherOriginalExtractors.fromHtml(
            "https://unknown.example/video/123",html).isEmpty())
    }
}
