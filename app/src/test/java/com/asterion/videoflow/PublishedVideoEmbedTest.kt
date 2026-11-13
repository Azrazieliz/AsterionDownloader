package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class PublishedVideoEmbedTest {
    @Test fun ordinarySelectedVideoPagesExposeTheirPublishedPlayer(){
        val page="https://jav.guru/1076295/start-663-original-episode/"
        val doc=Jsoup.parse("""
            <div id="player">
              <iframe src="https://media.example.org/embed/abc123"></iframe>
            </div>
            <iframe src="https://ads.example.org/click/ad" title="banner"></iframe>
        """.trimIndent(),page)
        assertEquals(listOf("https://media.example.org/embed/abc123"),
            WebDiscovery.publishedEmbeds(doc,page))
    }
    @Test fun separateFullscreenPlayerIsRetainedWithoutChangingProviderListing(){
        val page="https://mp4hentai.com/2026/11/a-real-video/"
        val doc=Jsoup.parse("""
          <iframe src="https://player.example.org/e/video55" allowfullscreen></iframe>
        """.trimIndent(),page)
        assertEquals(listOf("https://player.example.org/e/video55"),
            WebDiscovery.publishedEmbeds(doc,page))
    }
    @Test fun archivebateKeepsExactMixdropSelectionContract(){
        val page="https://archivebate.com/watch/123456/"
        val doc=Jsoup.parse("""
          <iframe src="https://mixdrop.ag/e/ABCdef123" allowfullscreen></iframe>
          <iframe src="https://ads.example.org/e/different" allowfullscreen></iframe>
        """.trimIndent(),page)
        assertEquals(listOf("https://mixdrop.ag/e/ABCdef123"),
            WebDiscovery.publishedEmbeds(doc,page))
    }
}
