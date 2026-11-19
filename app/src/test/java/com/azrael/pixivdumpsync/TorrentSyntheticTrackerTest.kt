package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No network call. Genuine index infohash is never modified by trackers. */
class TorrentSyntheticTrackerTest {
    @Test fun indexMagnetRetainsExactHashAndUsefulPublicTrackers() {
        val hash="0123456789abcdef0123456789abcdef01234567"
        val json="""{"results":[{"title":"Selected open-source release",
            "infohash":"$hash","seeders":12,"category":"software"}]}"""
        val hit=MultiTorrentSearch.bitsearch(json).single()
        assertTrue(hit.url.startsWith(
            "magnet:?xt=urn:btih:"+hash.uppercase()))
        assertEquals(hash,CloudTorrentPolicy.infoHash(hit.url))
        assertTrue(hit.url.contains("tracker.opentrackr.org"))
        assertTrue(hit.url.contains("open.demonii.com"))
        assertEquals(2,Regex("&tr=").findAll(hit.url).count())
    }
}
