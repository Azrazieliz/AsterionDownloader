package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class InternetWideYahooSearchTest {
    @Test fun publicWebHitsAreNotRestrictedToConfiguredProviders(){
        val html="""<div class="algo-sr"><div class="compTitle"><h3>
            <a href="https://unknown-publisher.example/episodes/rare-title-2026">
                Rare Title 2026 original</a></h3></div>
            <div class="compText">A video from an unconfigured site.</div></div>
            <div class="algo-sr"><div class="compTitle"><h3>
            <a href="https://r.search.yahoo.com/redirect?foo=bar">
                Rare Title 2026 result tracking redirect</a>
            </h3></div></div>
            <div class="related"><a href="https://ads.example.org/buy">
                Rare Title 2026 sponsored card</a></div>"""
        val hits=KeywordSources.parseWebIndex(
            html,"https://search.yahoo.com/search?p=rare+title","Yahoo Web")
        assertEquals(1,hits.size)
        assertEquals(
            "https://unknown-publisher.example/episodes/rare-title-2026",
            hits.single().url)
        assertEquals("Yahoo Web",hits.single().source)
    }

    @Test fun yahooAndBingSearchNextActualIndexPages(){
        val firstYahoo=KeywordSources.webIndexUrl(
            "rare title",VideoSearchArea.GENERAL,true,0)
        val nextYahoo=KeywordSources.webIndexUrl(
            "rare title",VideoSearchArea.GENERAL,true,1)
        val nextBing=KeywordSources.webIndexUrl(
            "rare title",VideoSearchArea.GENERAL,false,1)
        assertTrue(firstYahoo.startsWith(
            "https://search.yahoo.com/search?"))
        assertTrue(firstYahoo.contains("&b=1"))
        assertTrue(nextYahoo.contains("&b=11"))
        assertTrue(nextBing.contains("&first=51"))
        assertFalse((firstYahoo+nextYahoo+nextBing).contains("duckduckgo"))
    }

    @Test fun indexRequestsStayPrioritizedAheadOfOptionalPublisherEnrichment(){
        val tasks=KeywordSources.prioritizedTasks(
            listOf("Provider A" to 1,"Yahoo Web" to 2,
                "Provider B" to 3,"Bing Web" to 4))
        assertEquals(setOf("Bing Web","Yahoo Web"),
            tasks.take(2).map{it.first}.toSet())
    }
}
