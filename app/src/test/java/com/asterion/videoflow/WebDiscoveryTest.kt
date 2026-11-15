package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** URL validation only; transport for non-public hosts is configured separately. */
class WebDiscoveryTest {
    @Test fun validWebVideoUrl() {
        assertTrue(WebDiscovery.isUrl("https://www.example.com/videos/123456"))
        assertTrue(WebDiscovery.isUrl("https://media.example.com/sample.mp4?token=abc"))
    }
    @Test fun exactUrlAndSignedQueryArePreserved(){
        val url="https://www.example.com/watch/Video%20Name?token=a%2Bb&signature=987"
        assertEquals(url,WebDiscovery.normalize(url))
    }
    @Test fun keywordStringsRejectedAndHiddenUrlsRequireTransport(){
        assertFalse(WebDiscovery.isUrl("search:performer example"))
        assertTrue(WebDiscovery.isUrl("http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion"))
        assertNull(WebDiscovery.normalize("performer example videos"))
    }
    @Test fun rejectLocalNetworkAddresses(){
        listOf("http://localhost/a","http://127.0.0.1/a","http://192.168.0.2/a",
            "http://10.1.4.5/a","http://172.20.0.2/a",
            "http://169.254.169.254/latest/meta-data/").forEach{
            assertFalse("Expected a blocked local address: "+it,WebDiscovery.isUrl(it))
        }
    }
    @Test fun threeDHentaiNavigationIsNotAnIndexedVideo(){
        val doc=org.jsoup.Jsoup.parse("""
            <header><nav><a href="/inflation/">Inflation category</a></nav></header>
            <main><article class="post">
            <a href="/ntr-compilation/">NTR compilation post</a>
            </article><div class="category-grid">
            <a href="/inflation/">Category tile</a></div></main>""",
            "https://3dhentaix.com/")
        val links=doc.select("a[href]")
        assertFalse(WebDiscovery.listingAnchorEligible(
            "https://3dhentaix.com/",links[0]))
        assertTrue(WebDiscovery.listingAnchorEligible(
            "https://3dhentaix.com/",links[1]))
        assertFalse(WebDiscovery.listingAnchorEligible(
            "https://3dhentaix.com/",links[2]))
        assertTrue(WebDiscovery.listingAnchorEligible(
            "https://javideo.net/",links[0]))
    }
    @Test fun identifyTypicalVideoUrls(){
        assertTrue(WebDiscovery.isVideoPage("https://example.com/videos/abc123"))
        assertTrue(WebDiscovery.isVideoPage("https://www.pornhub.com/view_video.php?viewkey=1234"))
        assertTrue(WebDiscovery.isVideoPage("https://example.com/watch?v=123"))
        assertTrue(WebDiscovery.isVideoPage("https://videos.example.com/video.mp4"))
        assertFalse(WebDiscovery.isVideoPage("https://www.example.com/"))
    }
}
