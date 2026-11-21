package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class PornBuzSelectedFullHlsTest {
    private val selected="https://pornbuz.com/video/selected-original-scene-91071/"
    private val player="https://player.watchstreamus.com/embed/abCD12345"
    private val master="https://player.watchstreamus.com/hls/recording/master.m3u8?sig=opaque"
    @Test fun mainPlayerIsScopedToExactPublisherArticle(){
        val html="""<article><div class="entry-content">
            <div class="video-player-area with-sidebar-ads">
              <div class="video-player"><div class="responsive-player">
                <iframe src="$player"></iframe>
              </div></div>
              <aside><iframe src="https://creative.example/ad"></iframe></aside>
            </div></div></article>
            <div class="related"><iframe src="https://player.watchstreamus.com/embed/Other987"></iframe></div>"""
        assertEquals(player,PornBuzOriginalSource.selectedMainPlayer(selected,html))
        assertNull(PornBuzOriginalSource.selectedMainPlayer(selected,
            html.replace("<div class=\"responsive-player\">","<div class=\"other\">")))
    }

    @Test fun onlyVkPlaybackSourceCanSupplySignedMaster(){
        val html="""
            <script>window.VK_PLAYER_OPTS = {"autoplay":false};
            window.VK_PLAYBACK = {"format":"hls",
                "src":"$master","poster":"/poster.jpg","copies":[]};
            window.VK_ADS = {"url":"https://s.magsrv.com/ad.m3u8"};</script>
        """.trimIndent()
        assertEquals(master,PornBuzOriginalSource.selectedHls(player,html))
        assertNull(PornBuzOriginalSource.selectedHls(player,
            html.replace("window.VK_PLAYBACK","window.SOMETHING_ELSE")))
        assertNull(PornBuzOriginalSource.selectedHls(player,
            html.replace(master,"https://other.invalid/path/master.m3u8")))
    }
    @Test fun fullPublisherMultivariantMasterChoosesHighestPublishedQuality(){
        val playlist="#EXTM3U\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=854x480\\n"+
            "480p/index.m3u8?sig=abc\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720\\n"+
            "720p/index.m3u8?sig=def\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=3500000,RESOLUTION=1920x1080\\n"+
            "1080p/index.m3u8?sig=ghi\\n"
        val children=PornBuzOriginalSource.rankedChildManifests(master,playlist)
        assertEquals(3,children.size)
        assertEquals(
            "https://player.watchstreamus.com/hls/recording/1080p/index.m3u8?sig=ghi",
            children.first())
        assertEquals(
            "https://player.watchstreamus.com/hls/recording/480p/index.m3u8?sig=abc",
            children.last())
        // A master with multiple selected renditions is not ambiguous;
        // each actual child still has to prove full finalized runtime.
        assertNull(PornBuzOriginalSource.childManifest(master,playlist))
    }

    @Test fun maliciousOrPreviewMasterRenditionsCannotBecomeOriginals(){
        val playlist="#EXTM3U\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=9500000\\n"+
            "https://ad.example/video/master.m3u8\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=1000000\\n"+
            "https://player.watchstreamus.com/hls/preview-20sec.m3u8\\n"+
            "#EXT-X-STREAM-INF:BANDWIDTH=1800000\\n"+
            "https://player.watchstreamus.com/hls/recording/full.m3u8?sig=real\\n"
        val children=PornBuzOriginalSource.rankedChildManifests(master,playlist)
        assertEquals(listOf(
            "https://player.watchstreamus.com/hls/recording/full.m3u8?sig=real"),
            children)
        assertTrue(PornBuzOriginalSource.rankedChildManifests(master,
            "#EXTM3U\\n#EXTINF:5,\\nsegment.ts").isEmpty())
    }

    @Test fun oneFinalSelectedHlsWithLongRuntimePassesButTeaserFails(){
        val masterText="#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900000\n"+
            "scene/index.m3u8?sig=child\n"
        assertEquals("https://player.watchstreamus.com/hls/recording/scene/index.m3u8?sig=child",
            PornBuzOriginalSource.childManifest(master,masterText))
        val long="#EXTM3U\n"+(1..168).joinToString("\n"){
            "#EXTINF:11.94,\nsegment$it.ts"
        }+"\n#EXT-X-ENDLIST\n"
        assertNotNull(PornBuzOriginalSource.finalizedFullRuntimeSeconds(long))
        val teaser="#EXTM3U\n"+(1..4).joinToString("\n"){
            "#EXTINF:5.0,\nsegment$it.ts"
        }+"\n#EXT-X-ENDLIST\n"
        assertNull(PornBuzOriginalSource.finalizedFullRuntimeSeconds(teaser))
        assertTrue(VideoHlsDispatch.needsRuntimeBeforeSource(selected))
        assertFalse(VideoHlsDispatch.needsRuntimeBeforeSource(
            "https://pornbuz.com/"))
    }
}
