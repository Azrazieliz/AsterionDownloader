package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** URL validation only; transport for non-public hosts is configured separately. */
class WebDiscoveryTest {
    @Test fun dsysavHttp200EmptyNextShellMustUseActualBrowserCatalog(){
        val home="https://dsysav.com/en"
        val shell=org.jsoup.Jsoup.parse(
            "<html><body><div id='__next'></div></body></html>",home)
        assertTrue(WebDiscovery.dsysavCatalogNeedsBrowser(home,shell))
        val selected=org.jsoup.Jsoup.parse(
            "<main><a href='/en/watch?v=MxWT7NmlUoT'>Selected</a></main>",home)
        assertFalse(WebDiscovery.dsysavCatalogNeedsBrowser(home,selected))
        val otherSite=org.jsoup.Jsoup.parse(
            "<main><a href='https://dsysav.com.evil.example/en/watch?v=MxWT7NmlUoT'>Ad</a></main>",home)
        assertTrue(WebDiscovery.dsysavCatalogNeedsBrowser(home,otherSite))
        val wrongId=org.jsoup.Jsoup.parse(
            "<main><a href='/en/watch?v=wrong'>Other</a></main>",home)
        assertTrue(WebDiscovery.dsysavCatalogNeedsBrowser(home,wrongId))
        assertFalse(WebDiscovery.dsysavCatalogNeedsBrowser(
            "https://dsysav.com/en/watch?v=MxWT7NmlUoT",shell))
        assertFalse(WebDiscovery.dsysavCatalogNeedsBrowser(
            "http://dsysav.com/en",shell))
    }

    @Test fun selectedAvple402GetsAuthorizedBrowserNotBlindVideoFallback(){
        val selected="https://avple.tv/video/1000101415221"
        assertTrue(WebDiscovery.selectedBrowserFallbackAllowed(selected,402))
        assertFalse(WebDiscovery.selectedBrowserFallbackAllowed(
            "https://avple.tv/",402))
        assertFalse(WebDiscovery.selectedBrowserFallbackAllowed(
            "https://notfans.com/videos/117950/voulezj46/",402))
        assertFalse(WebDiscovery.selectedBrowserFallbackAllowed(
            "https://avple.tv.evil.example/video/1000101415221",402))
        assertFalse(WebDiscovery.selectedBrowserFallbackAllowed(selected,429))
        assertTrue(WebDiscovery.selectedBrowserFallbackAllowed(selected,403))
    }
    @Test fun laterPosterAnchorRecoversMissingFirstJavideoThumbnail(){
        val url="https://javideo.net/fc2ppv-5007485"
        val first=VideoHit("FC2PPV-5007485",url,null)
        val withCover=VideoHit("FC2PPV-5007485",url,
            "https://cdn.javideo.net/202611/fc2ppv-5007485-1794709028.webp")
        assertEquals(withCover.preview,WebDiscovery.withDiscoveredPreview(first,withCover).preview)
        assertEquals(withCover.preview,
            WebDiscovery.withDiscoveredPreview(withCover,first).preview)
    }
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
    @Test fun strictPlayerPublishersNeverIndexGenericPreviewMedia(){
        listOf("archivebate.com","helloporn.com","hentaihaven.xxx",
            "javideo.net").forEach { host ->
            assertTrue(host,WebDiscovery.requiresSelectedPlayerForListing(
                "https://$host/watch/selected-item"))
        }
        listOf("rule34video.com","hentaibros.net","fullporner.com",
            "freepornvideos.xxx").forEach { host ->
            assertFalse(host,WebDiscovery.requiresSelectedPlayerForListing(
                "https://$host/watch/selected-item"))
        }
        assertFalse(WebDiscovery.requiresSelectedPlayerForListing(
            "https://helloporn.com.evil.example/preview"))
    }
    @Test fun identifyTypicalVideoUrls(){
        assertTrue(WebDiscovery.isVideoPage("https://example.com/videos/abc123"))
        assertTrue(WebDiscovery.isVideoPage("https://www.pornhub.com/view_video.php?viewkey=1234"))
        assertTrue(WebDiscovery.isVideoPage("https://example.com/watch?v=123"))
        assertTrue(WebDiscovery.isVideoPage("https://videos.example.com/video.mp4"))
        assertFalse(WebDiscovery.isVideoPage("https://www.example.com/"))
    }
}
