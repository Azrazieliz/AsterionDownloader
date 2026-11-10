package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoTransferReliabilityTest {
    @Test fun multilineExtractorJsonAfterWarnings(){
        val payload = """
          WARNING: trying a different format
          {
            "title": "Full original {not a placeholder}",
            "duration": 864,
            "formats": [{"format_id": "best"}]
          }
          extracted
        """.trimIndent()
        val json=VideoMetadataOutput.parse(payload)
        assertNotNull(json)
        assertEquals(864,json!!.getInt("duration"))
    }
    @Test fun junkAndIncompleteJsonNeverCountsAsMetadata(){
        assertNull(VideoMetadataOutput.parse("WARNING no JSON from extractor"))
        assertNull(VideoMetadataOutput.parse("WARNING {invalid}"))
    }
    @Test fun boundedProgressTimeouts(){
        assertNull(VideoTransferLimits.expired("metadata",10_000L,0L))
        assertNotNull(VideoTransferLimits.expired("metadata",50_000L,0L))
        assertNull(VideoTransferLimits.expired("transfer",170_000L,15_000L))
        assertNotNull(VideoTransferLimits.expired("transfer",170_000L,110_000L))
    }
    @Test fun authDoesNotAutomaticallyRetry(){
        assertEquals(VideoFailurePolicy.Kind.AUTH,VideoFailurePolicy.classify("HTTP Error 403 Forbidden"))
        assertTrue(VideoFailurePolicy.actionable(VideoFailurePolicy.Kind.AUTH))
        assertEquals(VideoFailurePolicy.Kind.RATE_LIMIT,VideoFailurePolicy.classify("HTTP Error 429"))
        assertFalse(VideoFailurePolicy.actionable(VideoFailurePolicy.Kind.RATE_LIMIT))
        assertEquals(VideoFailurePolicy.Kind.TRANSIENT,VideoFailurePolicy.classify("Connection timed out"))
        assertTrue(VideoFailurePolicy.delayMs(VideoFailurePolicy.Kind.RATE_LIMIT,1)>=600_000)
        assertEquals(VideoFailurePolicy.Kind.METADATA,
            VideoFailurePolicy.classify("Video extractor returned no valid metadata JSON"))
        assertEquals(2,VideoFailurePolicy.maxRetries(VideoFailurePolicy.Kind.METADATA))
    }
}
