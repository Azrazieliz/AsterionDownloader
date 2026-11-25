package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Search discovery must not be constrained by installed provider adapters. */
class InternetWideDiscoveryRegressionTest {
    @Test fun generalVideoWebSearchIsNotRestrictedToAdultSiteCatalogs(){
        assertTrue(KeywordSources.siteDefinitions(VideoSearchArea.GENERAL).isEmpty())
        val url=KeywordSources.webIndexUrl(
            "documentary 2026",VideoSearchArea.GENERAL,0)
        assertTrue(url.contains("q=documentary+2026+video"))
        assertFalse(url.contains("adult"))
        assertFalse(url.contains("hentai"))
        val adult=KeywordSources.webIndexUrl(
            "documentary 2026",VideoSearchArea.PORN,0)
        assertTrue(adult.contains("adult+video"))
    }

    @Test fun nextWebPageUsesRealSearchEngineOffsetsWithoutYahoo(){
        val first=KeywordSources.webIndexUrl(
            "rare show",VideoSearchArea.GENERAL,0)
        val third=KeywordSources.webIndexUrl(
            "rare show",VideoSearchArea.GENERAL,2)
        assertTrue(first.startsWith("https://www.bing.com/search?"))
        assertTrue(first.contains("first=1"))
        assertTrue(third.contains("first=101"))
        assertFalse(third.contains("yahoo",ignoreCase=true))
        assertThrows(IllegalArgumentException::class.java){
            KeywordSources.webIndexUrl(
                "rare show",VideoSearchArea.GENERAL,-1)
        }
    }

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

    @Test fun retiredFourKGirlCannotPublishLazyFullCover(){
        val base="https://www.4kgirl.com/"
        val html="""
          <article class="gridwp-grid-post">
            <h3 class="entry-title"><a href="/actual-gallery/">
              Actual gallery
            </a></h3>
            <img src="/loading.gif"
                 data-lazy-src="https://www.4kgirl.com/wp-content/uploads/actual-600.webp"
                 alt="Actual gallery"/>
          </article>
        """.trimIndent()
        val entries=SiteBackfillProfiles.entries(
            org.jsoup.Jsoup.parse(html,base),base,"COSPLAY",1L).orEmpty()
        assertEquals(1,entries.size)
        assertTrue(entries.single().preview.orEmpty().endsWith("actual-600.webp"))
        val cards=KyoraCreatorPreviews.parse(html,base)
        assertTrue("Retired provider must not publish cards",cards.isEmpty())
    }

    @Test fun retiredFourKGirlSiblingThumbnailCannotResurface(){
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
        assertTrue(found.isEmpty())
    }
}
