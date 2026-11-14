package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class UnresolvablePublisherRetryPolicyTest {
    @Test fun unsupportedPublisherDoesNotLoopAutomaticRetries(){
        val errors=listOf(
            "Python extractor: Video extractor cannot resolve this publisher's current player",
            "No verified original found on this page",
            "No publisher original or supported player found",
            "Selected video yielded no original bytes after 80s"
        )
        for(reason in errors){
            val kind=VideoFailurePolicy.classify(reason)
            assertEquals(reason,VideoFailurePolicy.Kind.UNSUPPORTED,kind)
            assertTrue(VideoFailurePolicy.actionable(kind))
        }
    }
    @Test fun ordinaryNetworkOutageRemainsRetryable(){
        assertFalse(VideoFailurePolicy.actionable(
            VideoFailurePolicy.classify("Temporary connection reset by peer")))
    }
}
