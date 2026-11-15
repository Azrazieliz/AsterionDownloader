package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class SessionHtmlEnvelopeTest {
    @Test fun largeSelectedPlayerDomSurvivesCrossProcessCompression(){
        val html="<html><video src='https://cdn.example/video.mp4'></video>"+
            "<main>selected-player</main>".repeat(16_000)+"</html>"
        assertTrue(html.length>SessionHtmlEnvelope.RAW_LIMIT)
        val packed=SessionHtmlEnvelope.compress(html)
        assertNotNull("Readable large publisher pages must not fail Binder's raw HTML cap",packed)
        assertEquals(html,SessionHtmlEnvelope.expand(packed!!))
    }
    @Test fun neverAcceptsUnboundedOrMalformedPackets(){
        assertNull(SessionHtmlEnvelope.expand("bad-base64!"))
        assertNull(SessionHtmlEnvelope.expand("A".repeat(SessionHtmlEnvelope.ENCODED_LIMIT+1)))
        assertNull(SessionHtmlEnvelope.compress("x".repeat(SessionHtmlEnvelope.DECODED_LIMIT+1)))
    }
}
