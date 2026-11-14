package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class SelectedBrowserMediaTest {
    @Test fun shortPreviewsCannotBeMislabelledAsCompleteEpisodes(){
        val source="https://hentaibros.net/deco-x-deco-the-animation-episode-1/"
        val html="""<main><div class="video-player">
            <video src="https://cdn.example.net/teaser.mp4" data-duration="30"></video>
        </div></main>"""
        val checked=WebDiscovery.selectedBrowserMedia(html,source)
        assertNull(checked.original)
        assertTrue(checked.previewOnly)
    }
    @Test fun realPublishedPlayerWithLongDurationIsSelectable(){
        val source="https://3dhentaix.com/ntr-compilation/"
        val html="""<main><div class="video-player">
            <video src="https://cdn.example.net/original.mp4?signature=opaque"
                data-duration="600"></video>
        </div></main>"""
        val checked=WebDiscovery.selectedBrowserMedia(html,source)
        assertEquals("https://cdn.example.net/original.mp4?signature=opaque",
            checked.original)
    }
    @Test fun fakePrivateAndPosterUrlsCannotBecomeOriginal(){
        val html="""<main><div class="video-player">
            <video src="http://127.0.0.1/private.mp4" data-duration="500"></video>
            <video src="https://images.example.net/poster.webp" data-duration="800"></video>
        </div></main>"""
        assertNull(WebDiscovery.selectedBrowserMedia(
            html,"https://3dhentaix.com/ntr-compilation/").original)
    }
}
