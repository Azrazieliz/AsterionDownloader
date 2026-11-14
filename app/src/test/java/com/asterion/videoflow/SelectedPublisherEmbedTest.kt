package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class SelectedPublisherEmbedTest {
    @Test fun selectedUnknownSlugCanStillResolvePublisherOwnedPlayer() {
        val selected="https://javideo.net/current-episode-slug"
        val document=Jsoup.parse("""
            <div class="video-player">
                <iframe src="https://player.example.org/embed/episode-42"
                    allowfullscreen title="Watch episode"></iframe>
            </div>
        """.trimIndent(),selected)
        assertTrue(WebDiscovery.publishedEmbeds(document,selected).isEmpty())
        assertEquals(listOf("https://player.example.org/embed/episode-42"),
            WebDiscovery.publishedEmbeds(document,selected,true))
    }
    @Test fun advertisingIframeIsNeverAcceptedAsTheSelectedOriginal() {
        val selected="https://javideo.net/current-episode-slug"
        val document=Jsoup.parse("""
            <div class="video-player">
                <iframe src="https://ads.example.org/banner/"
                    allowfullscreen title="Advertisement"></iframe>
            </div>
        """.trimIndent(),selected)
        assertTrue(WebDiscovery.publishedEmbeds(document,selected,true).isEmpty())
    }
}
