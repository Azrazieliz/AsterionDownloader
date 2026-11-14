package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class EpornerFirstPartyFormatsTest {
    @Test fun publishedHashUsesFourBase36Chunks() {
        assertEquals("0000",
            EpornerOriginalSource.encodePublishedHash("00000000000000000000000000000000"))
        assertEquals("1111",
            EpornerOriginalSource.encodePublishedHash("00000001000000010000000100000001"))
        assertNull(EpornerOriginalSource.encodePublishedHash("not-a-32-byte-hash"))
        assertEquals("00000001000000010000000100000001",
            EpornerOriginalSource.publishedHash(
                """<script>var hash = "00000001000000010000000100000001";</script>"""))
    }
    @Test fun exactFirstPartyJsonSelectsMp4AndHlsOnly() {
        val raw="""{"available":true,"sources":{
          "mp4":{"1080p":{"src":"https://cdn.example/selected_1080.mp4"},
                 "720p":{"src":"https://cdn.example/selected_720.mp4"}},
          "hls":{"1080p":{"src":"https://cdn.example/selected.m3u8?token=abc"}},
          "previews":{"thumb":{"src":"https://cdn.example/thumb.jpg"}},
          "ads":{"other":{"src":"https://cdn.example/preview.mp4"}}
        }}"""
        val sources=EpornerOriginalSource.sourceUrls(raw)
        assertTrue(sources.contains("https://cdn.example/selected_1080.mp4"))
        assertTrue(sources.contains("https://cdn.example/selected.m3u8?token=abc"))
        assertFalse(sources.contains("https://cdn.example/thumb.jpg"))
        assertFalse(sources.contains("https://cdn.example/preview.mp4"))
    }
    @Test fun blockedSourceAndUnsupportedSchemesRejected() {
        assertTrue(EpornerOriginalSource.sourceUrls(
            """{"available":false,"sources":{"mp4":{"x":{"src":"https://cdn.example/selected.mp4"}}}}"""
        ).isEmpty())
        assertTrue(EpornerOriginalSource.sourceUrls(
            """{"available":true,"sources":{"mp4":{"x":{"src":"http://127.0.0.1/original.mp4"}}}}"""
        ).isEmpty())
    }
}
