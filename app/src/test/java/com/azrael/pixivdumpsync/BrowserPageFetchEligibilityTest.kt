package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class BrowserPageFetchEligibilityTest {
    @Test fun mp4HentaiPublishedHosterHasExactPublicBrowserRoute(){
        assertTrue(BrowserPageFetch.publicEligible(
            "https://upfiles.com/nGfW"))
        assertTrue(BrowserPageFetch.publicEligible(
            "https://upfilesgo.com/nGfW"))
        assertTrue(BrowserPageFetch.publicEligible(
            "https://www.mirrored.to/files/DF7298C6/file.mp4_links"))
    }
    @Test fun exactCosplayTelePageEligibleWithoutRetiredCamStreams(){
        assertTrue(BrowserPageFetch.publicEligible(
            "https://cosplaytele.com/selected-cosplay-set/"))
        assertFalse(BrowserPageFetch.publicEligible(
            "https://camstreams.tv/videos/1363858/retired-recording/"))
        assertFalse(BrowserPageFetch.publicEligible(
            "https://cosplaytele.com.evil.example/selected-cosplay-set/"))
    }
    @Test fun publicGalleryWebviewFallbackIsLimitedToFirstPartyHosts(){
        assertTrue(BrowserPageFetch.publicEligible(
            "https://www.4kgirl.com/example-cosplay/"))
        assertTrue(BrowserPageFetch.publicEligible(
            "https://www.4khd.com/content/20/selected.html"))
        assertFalse(BrowserPageFetch.publicEligible(
            "https://4kgirl.com.bad.example/example-cosplay/"))
        assertFalse(BrowserPageFetch.publicEligible(
            "http://4kgirl.com/example-cosplay/"))
    }

    @Test fun hosterLookalikesAndPortsCannotUsePublicWebview(){
        for(raw in listOf(
            "https://upfiles.com.evil.example/nGfW",
            "https://upfiles.com:8443/nGfW",
            "http://upfiles.com/nGfW",
            "https://user:pass@upfiles.com/nGfW",
            "https://127.0.0.1/nGfW"
        ))assertFalse(raw,BrowserPageFetch.publicEligible(raw))
    }
}
