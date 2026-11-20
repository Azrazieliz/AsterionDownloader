package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Test

class HelloPornFailurePolicyTest {
    @Test fun selectedPlayerRejectsUnreachableWithoutFakeOriginal(){
        val good="https://helloporn.com/video-123456-selected-video"
        val selected="https://voe.sx/e/abcdefgh"
        assertEquals(false,
            SiteSpecificOriginalSources.selectedExtractorFrame(good,selected))
        for(untrusted in listOf(
            "https://voe.sx.evil.example/e/abcdefgh",
            "http://voe.sx/e/abcdefgh",
            "https://voe.sx/",
            "https://voe.sx/e/abcdefgh?advert=1"
        ))assertEquals(false,
            SiteSpecificOriginalSources.selectedExtractorFrame(good,untrusted))
    }

    @Test fun realNoByteRootCausesRespectRetryPolicy(){
        assertEquals(VideoFailurePolicy.Kind.AUTH,
            VideoFailurePolicy.classify(
                "Authentication required: VOE player requires its own authorized session"))
        assertEquals(VideoFailurePolicy.Kind.RATE_LIMIT,
            VideoFailurePolicy.classify(
                "HTTP 429 rate limit from the selected VOE host"))
        assertEquals(VideoFailurePolicy.Kind.TRANSIENT,
            VideoFailurePolicy.classify(
                "Network unreachable: selected VOE host cannot be accessed"))
        assertEquals(VideoFailurePolicy.Kind.UNSUPPORTED,
            VideoFailurePolicy.classify(
                "Selected player metadata unavailable; no full original"))
    }
}
