package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

/** Pure parsing checks; actual public exit IP requires a connected phone. */
class NetworkRouteTest {
    @Test fun canonicalPerSiteRouteKeys() {
        assertEquals("eporner.com",NetworkRoute.siteKey("https://www.eporner.com/video-example/"))
        assertEquals("archivebate.com",NetworkRoute.siteKey("archivebate.com"))
        assertEquals("",NetworkRoute.siteKey("not a domain / invalid url"))
    }
}
