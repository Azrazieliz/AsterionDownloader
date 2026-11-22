package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class InternetWideYahooSearchTest {
    @Test fun realBingOrganicRedirectRecoversOriginalPublisherNotBingTracking(){
        val publisher="https://independent.example/episodes/rare-title-2026"
        val encoded=java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(publisher.toByteArray(Charsets.UTF_8))
        val tracking="https://www.bing.com/ck/a?!&amp;&amp;p=publisher"+
            "&amp;u=a1"+encoded+"&amp;ntb=1"
        val html="<li class='b_algo'><h2><a href='"+tracking+
            "'>Rare Title 2026 original</a></h2></li>"
        val hits=KeywordSources.parseWebIndex(
            html,"https://www.bing.com/search?q=rare+title","Bing Web")
        assertEquals(1,hits.size)
        assertEquals(publisher,hits.single().url)
        assertEquals("Bing Web",hits.single().source)
    }

    @Test fun realYahooOrganicRedirectRecoversOnlyEncodedRuDestination(){
        val publisher="https://independent.example/episodes/rare-title-2026"
        val encoded=java.net.URLEncoder.encode(publisher,"UTF-8")
        val link="https://r.search.yahoo.com/_ylt=public/RU="+encoded+
            "/RK=2/RS=publisher"
        val html="<div class='algo-sr'><div class='compTitle'>"+
            "<a href='"+link+"'>Rare Title 2026 original</a>"+
            "</div></div>"
        val hits=KeywordSources.parseWebIndex(
            html,"https://search.yahoo.com/search?p=rare+title","Yahoo Web")
        assertEquals(1,hits.size)
        assertEquals(publisher,hits.single().url)
    }

    @Test fun searchIndexWrappersNeverPromoteUnknownRedirectsOrAdHosts(){
        val bad=listOf(
            "https://www.bing.com/ck/a?u=a1abc",
            "https://www.bing.com/ck/a?u=a1aHR0cDovL2Fkcy5leGFtcGxlL2J1eQ",
            "https://www.bing.com/ck/a?u=a1aHR0cHM6Ly93d3cuYmluZy5jb20vc2VhcmNoP3E9YWQ",
            "https://www.bing.com/redirect?u=a1aHR0cHM6Ly9hLmV4YW1wbGUvdmlkZW8",
            "https://r.search.yahoo.com/redirect?foo=bar",
            "https://ads.example.com/track?url=https://independent.example/video"
        )
        for(link in bad)assertNull(link,
            KeywordSources.organicPublisherDestination(link,
                if(link.contains("bing.com"))"Bing Web" else "Yahoo Web"))
    }

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
