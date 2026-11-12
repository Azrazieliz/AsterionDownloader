package com.asterion.videoflow

import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

/** Tests actual publisher-owned player markup, not inferred media URLs. */
class PublisherEmbedsTest {
    @Test fun legitimateUncensoredHentaiPlayerIsRecognized(){
        val source="https://uncensoredhentai.xxx/watch/example-episode/"
        val doc=Jsoup.parse("""
            <main><iframe src="https://nhplayer.com/v/F3w6jnf5VYygHXM/"
              allowfullscreen title="Watch episode"></iframe></main>
            <iframe src="https://ads.example.com/click" allowfullscreen></iframe>
        """.trimIndent(),source)
        assertEquals(listOf("https://nhplayer.com/v/F3w6jnf5VYygHXM/"),
            WebDiscovery.publishedEmbeds(doc,source))
    }

    @Test fun siteNavigationAndForeignPlayersAreNotAdmitted(){
        val source="https://uncensoredhentai.xxx/watch/example-episode/"
        val html="""<iframe src="https://nhplayer.com/v/x/" allowfullscreen></iframe>
          <iframe src="https://nhplayer.com.evil.example/v/F3w6jnf5VYygHXM/"
             allowfullscreen></iframe>
          <iframe src="https://nhplayer.com/v/F3w6jnf5VYygHXM/"
             title="advertisement"></iframe>"""
        assertTrue(WebDiscovery.publishedEmbeds(Jsoup.parse(html,source),source).isEmpty())
        assertTrue(WebDiscovery.publishedEmbeds(Jsoup.parse(html,
            "https://3dhentaix.com/video/a"),"https://3dhentaix.com/video/a")
            .isEmpty())
    }
}
