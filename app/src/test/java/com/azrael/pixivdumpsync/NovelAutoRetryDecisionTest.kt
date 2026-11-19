package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAutoRetryDecisionTest {
    @Test fun transientFailuresRetryPromptlyButNeverForever() {
        val retry=NovelEpubQueue.RetryDecision
        for(error in listOf(
            "Connection timed out", "Connection reset by peer",
            "No route to host", "HTTP 503", "HTTP 429 Too many requests",
            "UnknownHostException", "Unexpected EOF on chapter 19"
        )){
            assertTrue(error,retry.retryable(error,1))
            assertTrue(error,retry.retryable(error,5))
            assertFalse(error,retry.retryable(error,6))
        }
        assertEquals(15_000L,retry.delay("Connection timed out",1))
        assertEquals(30_000L,retry.delay("Connection reset",2))
        assertEquals(60_000L,retry.delay("HTTP 503",3))
        assertEquals(120_000L,retry.delay("HTTP 500",4))
        assertEquals(240_000L,retry.delay("HTTP 504",5))
        assertEquals(600_000L,retry.delay("HTTP 429",1))
    }

    @Test fun missingChaptersAndSignInMustNotBeBlindlyRetried() {
        val retry=NovelEpubQueue.RetryDecision
        for(error in listOf(
            "Missing 4 numbered chapters; refusing incomplete EPUB",
            "Chapter not found", "HTTP 404", "HTTP 410",
            "Login required", "Authenticated site session required",
            "Novel parse error", "Unsupported novel site",
            "All chapters downloaded but EPUB integrity verification failed"
        )){
            assertFalse(error,retry.retryable(error,1))
        }
    }
}
