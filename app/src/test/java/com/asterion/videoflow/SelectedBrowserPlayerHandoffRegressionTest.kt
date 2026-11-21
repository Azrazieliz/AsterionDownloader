package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Native selected-publisher extractors must accept exactly the sanitized
 * browser snapshot emitted by BrowserPageFetch, not an unrelated video card.
 * These are pure parser tests: no network, no APK, no synthetic originals.
 */
class SelectedBrowserPlayerHandoffRegressionTest {
    @Test fun xChinaSelectedSameIdHlsSurvivesRenderedBrowserSnapshot(){
        val selected="https://en.xchina.co/video/id-abcdef123456.html"
        val legitimate="https://en.xchina.co/hls/abcdef123456/master.m3u8"
        val compact="""
          <html><head><meta name="asterion-selected-xchina-hls" content="true">
          </head><body><main><div id="video-player" data-src="$legitimate">
          </div></main></body></html>
        """
        val result=PublisherOriginalExtractors.fromHtml(selected,compact)
        assertEquals(1,result.size)
        assertEquals(legitimate,result.single().url)
        val unrelated=compact.replace("abcdef123456/master.m3u8",
            "ffffffffffff/master.m3u8")
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,unrelated).isEmpty())
    }

    @Test fun pimpBunnySelectedFlashvarsSurviveExactRenderedKvsSnapshot(){
        val selected="https://pimpbunny.com/videos/selected-real-scene-12345/"
        val media="https://pimpbunny.com/get_file/0/real-selected-scene-original-123456789.mp4"
        val html="""
          <html><body><main><div id="kt_player"></div>
          <script>var kt_player = {
              video_id: "24680",
              video_url: "$media"
          };</script></main>
          <aside class="related">
            <video src="https://pimpbunny.com/contents/videos_screenshots/preview.mp4"></video>
          </aside></body></html>
        """
        val result=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(1,result.size)
        assertEquals(media,result.single().url)
        val mismatch=html.replace(media,
            "https://pimpbunny.com/contents/videos_screenshots/preview.mp4")
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,mismatch).isEmpty())
    }

    @Test fun pornAvhdSelectedIframeIsPreservedWithoutAcceptingAdFrame(){
        val selected="https://pornavhd.com/2026/11/21/real-selected-scene/"
        val media="https://video.playerrecord.cfd/e/AbcDef123456789"
        val html="""
          <html><body><main><div class="video-player">
            <iframe src="$media"></iframe>
          </div></main><aside class="recommendations">
            <iframe src="https://advertisement.example/e/unrelated"></iframe>
          </aside></body></html>
        """
        val result=PublisherOriginalExtractors.fromHtml(selected,html)
        assertEquals(1,result.size)
        assertEquals(media,result.single().url)
        val forged=html.replace("video.playerrecord.cfd",
            "video.playerrecord.cfd.evil.example")
        assertTrue(PublisherOriginalExtractors.fromHtml(selected,forged).isEmpty())
    }
}
