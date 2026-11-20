package com.azrael.pixivdumpsync

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformerMediaAttributionTest {
    private val profile="https://www.pornhub.com/model/tina-kitsune"
    private val owned="https://www.pornhub.com/view_video.php?viewkey=ph1111"
    private val related="https://www.pornhub.com/view_video.php?viewkey=ph2222"

    @Test fun ownedListingQualifiesButTitleMatchedRecommendationDoesNot() {
        val doc=Jsoup.parse("""
            <html><head><link rel="canonical" href="$profile"></head>
            <body><h1>Tina Kitsune</h1><main>
              <div class="video-grid"><a href="$owned">Original upload</a></div>
              <section class="recommended"><div class="video-grid">
                <a href="$related">Tina Kitsune compilation by someone else</a>
              </div></section>
            </main></body></html>
        """.trimIndent(),profile)
        val urls=PerformerMediaAttribution.attributableUrls("Tina Kitsune",profile,doc)
        assertEquals(setOf(SearchRanking.canonicalKey(owned)),urls)
    }

    @Test fun unrelatedNameOnProfileNeverEstablishesIdentity() {
        val doc=Jsoup.parse("""
            <h1>Different Model</h1><main><div class="video-grid">
            <a href="$owned">Tina Kitsune movie</a></div></main>
        """.trimIndent(),profile)
        assertTrue(PerformerMediaAttribution.attributableUrls(
            "Tina Kitsune",profile,doc).isEmpty())
    }

    @Test fun crossProviderAndBareTitleEvidenceCannotAutoQueue() {
        val doc=Jsoup.parse("""
            <h1>Tina Kitsune</h1><main><div class="video-grid">
                <a href="https://watchporn.to/videos/elsewhere">Offsite</a>
            </div><p><a href="$owned">Tina Kitsune video</a></p></main>
        """.trimIndent(),profile)
        assertTrue(PerformerMediaAttribution.attributableUrls(
            "Tina Kitsune",profile,doc).isEmpty())
    }
}
