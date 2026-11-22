package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class InternetWideBingSearchTest {
    @Test fun bingOrganicRedirectRecoversOriginalPublisher() {
        val expected="https://independent.example/episodes/rare-title-2026"
        val b64=java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(expected.toByteArray(Charsets.UTF_8))
        val result="<li class='b_algo'><h2><a href='"+
            "https://www.bing.com/ck/a?u=a1"+b64+
            "'>Rare Title 2026 original</a></h2></li>"
        val hits=KeywordSources.parseWebIndex(result,
            "https://www.bing.com/search?q=rare+title","Bing Web")
        assertEquals(listOf(expected),hits.map{it.url})
        assertEquals("Bing Web",hits.single().source)
    }

    @Test fun actualPublisherPagesDoNotNeedPreconfiguredProviders() {
        val html="<li class='b_algo'><h2><a href='"+
            "https://independent.example/episodes/rare-title-2026"+
            "'>Rare Title 2026 original</a></h2></li>"+
            "<li class='b_ad'><h2><a href='https://ads.example.org/buy'>"+
            "Rare Title 2026 sponsored card</a></h2></li>"
        val hits=KeywordSources.parseWebIndex(html,
            "https://www.bing.com/search?q=rare+title","Bing Web")
        assertEquals(1,hits.size)
        assertEquals("https://independent.example/episodes/rare-title-2026",
            hits.single().url)
    }

    @Test fun measurementAndUnsafeLinksAreNotOriginalPublishers(){
        val bad=listOf(
            "https://www.bing.com/ck/a?u=a1abc",
            "https://www.bing.com/ck/a?u=a1aHR0cDovL2Fkcy5leGFtcGxlL2J1eQ",
            "https://www.bing.com/ck/a?u=a1aHR0cHM6Ly93d3cuYmluZy5jb20vc2VhcmNoP3E9YWQ",
            "https://www.bing.com/redirect?u=a1aHR0cHM6Ly9hLmV4YW1wbGUvdmlkZW8"
        )
        for(link in bad)
            assertNull(KeywordSources.organicPublisherDestination(link,"Bing Web"))
    }

    @Test fun bingSearchUsesActualSecondResultPage(){
        val pageOne=KeywordSources.webIndexUrl(
            "rare title",VideoSearchArea.GENERAL,0)
        val pageTwo=KeywordSources.webIndexUrl(
            "rare title",VideoSearchArea.GENERAL,1)
        assertTrue(pageOne.contains("&first=1"))
        assertTrue(pageTwo.contains("&first=51"))
    }

    @Test fun bingIsPrioritizedBeforeSupplementaryPublishers(){
        val tasks=KeywordSources.prioritizedTasks(
            listOf("Provider A" to 1,"Bing Web" to 2,
                "Provider B" to 3))
        assertEquals("Bing Web",tasks.first().first)
    }
}
