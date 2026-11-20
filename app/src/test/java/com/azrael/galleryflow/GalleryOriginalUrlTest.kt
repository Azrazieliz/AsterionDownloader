package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryOriginalUrlTest {
    @Test fun unicodeCosplayPhotoPathIsEncodedBeforeHttpRequest() {
        val raw="https://mitaku.net/wp-content/uploads/2026/11/浴衣-01.jpg"
        val wire=GalleryOriginalUrl.requestTarget(raw)
        assertTrue(wire.startsWith(
            "https://mitaku.net/wp-content/uploads/2026/11/"))
        assertTrue(wire.contains("%"))
        assertTrue(wire.endsWith("-01.jpg"))
        assertFalse(wire.contains("浴衣"))
    }
    @Test fun signedExistingPercentEscapesStayUnchanged() {
        val url="https://cdn.example.test/f/%E6%B5%B4.jpg?verify=a%2Fb%3Dc&token=abc"
        assertEquals(url,GalleryOriginalUrl.requestTarget(url))
    }
    @Test fun unsafeProtocolsAndNoHostFailClosed() {
        assertThrows(IllegalArgumentException::class.java){
            GalleryOriginalUrl.requestTarget("file:///tmp/photo.jpg")
        }
    }
}
