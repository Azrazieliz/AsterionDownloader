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
