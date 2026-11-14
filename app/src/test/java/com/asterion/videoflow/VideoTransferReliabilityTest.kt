package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoTransferReliabilityTest {
    @Test fun archivebateSelectedWatchDoesNotMistakeRecommendedClipsForOriginal(){
        // Real page /watch/16458050 has recommendation <video> sources and
        // one separate publisher Mixdrop iframe. Never resolve recommendation
        // MP4s as the selected recording simply because they play.
        assertNull(WebDiscovery.directMedia("https://archivebate.com/watch/16458050"))
    }
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
    @Test fun extractorErrorsRemainActionableWithoutLeakingMediaTokens(){
        val raw="ERROR: HTTP Error 403: https://videos.example/full.mp4?token=PRIVATE"
        val result=VideoExtractorErrors.message(raw,1,"metadata")
        assertTrue(result.contains("403"))
        assertFalse(result.contains("PRIVATE"))
        assertFalse(result.contains("videos.example"))
    }
    @Test fun transientExtractorFailureReportsRetryableHostClass(){
        val result=VideoExtractorErrors.message("ERROR: HTTP Error 502 Bad Gateway",1,"transfer")
        assertTrue(result.contains("5xx"))
    }
    @Test fun epornerPlayerHashFailureRequiresActionNotEndlessRetry(){
        val log="ERROR: [Eporner] video: Unable to extract hash"
        val message=VideoExtractorErrors.message(log,1,"metadata")
        assertTrue(message.contains("Eporner"))
        assertTrue(message.contains("CONNECT ACCOUNT"))
        assertFalse(message.contains("video:"))
        assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,
            VideoFailurePolicy.classify(message))
        assertTrue(VideoFailurePolicy.actionable(VideoFailurePolicy.classify(message)))
    }
    @Test fun reproducibleNoFormatAndShortIdFailuresNeedActionNotLoop(){
        val errors=listOf(
            "ERROR: [XVideos] No video formats found!",
            "ERROR: [PeekVids] Unable to extract short video ID")
        for(error in errors){
            val category=VideoFailurePolicy.classify(error)
            assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,category)
            assertTrue(VideoFailurePolicy.actionable(category))
        }
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
