package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Search discovery must not be constrained by installed provider adapters. */
class InternetWideDiscoveryRegressionTest {
    @Test fun unfamiliarPublisherArticleSurvivesIndexedSearch(){
        val html="""
          <li class="b_algo"><h2>
            <a href="https://unknown-publisher.example/episodes/rare-show-2026">
              Rare Show 2026 full episode
            </a></h2><div class="b_caption"><p>Official post.</p></div></li>
          <li class="b_algo"><h2>
            <a href="https://www.bing.com/search?q=rare+show">Rare Show 2026 search</a>
          </h2></li>
        """.trimIndent()
        val hits=KeywordSources.parseWebIndex(html,
            "https://www.bing.com/search?q=rare+show","Bing Web")
        assertTrue(hits.any{it.url.contains("unknown-publisher.example")})
        val ranked=SearchRanking.deduplicateDiscovery("Rare Show 2026",hits)
        assertEquals(1,ranked.size)
        assertEquals("https://unknown-publisher.example/episodes/rare-show-2026",
            ranked.single().url)
        assertFalse(SearchRanking.isVideoUrl(ranked.single().url))
    }

    @Test fun internetWideDiscoveryRetainsRetirementAndNavigationSafeguards(){
        val hits=listOf(
            RankedHit("Rare Show","https://unknown.example/story/rare-show","Web"),
            RankedHit("Rare Show","https://unknown.example/category/rare-show","Web"),
            RankedHit("Rare Show","https://helloporn.com/video-555-rare-show","Web"),
            RankedHit("Rare Show","https://www.bing.com/search?q=rare","Web")
        )
        val urls=SearchRanking.deduplicateDiscovery("Rare Show",hits).map{it.url}
        assertEquals(listOf("https://unknown.example/story/rare-show"),urls)
    }

    @Test fun fourKGirlSiblingThumbnailIsFoundOnPublisherListing(){
        val base="https://www.4kgirl.com/"
        val html="""
          <div class="gridwp-grid-post">
            <h3 class="entry-title"><a href="/example-cosplay-set/">
              Example cosplay set
            </a></h3>
            <img data-src="https://www.4kgirl.com/wp-content/uploads/example-640.jpg"
                 src="/loading.gif" alt="Example cosplay set"/>
          </div>
        """.trimIndent()
        val found=KyoraCreatorPreviews.parse(html,base)
        assertEquals(1,found.size)
        assertEquals("https://www.4kgirl.com/example-cosplay-set/",found.single().url)
        assertTrue(found.single().cover.contains("example-640.jpg"))
    }
}
