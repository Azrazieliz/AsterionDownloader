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
    @Test fun publisherRegionGateRequiresUserActionNotAutomaticRetry(){
        val reasons=listOf(
            "Pornhub's selected route still exits in France (FR).",
            "Pornhub requires a real remote exit; connect an Android VPN",
            "Active Android VPN required for this site",
            "Selected public exit country could not be verified")
        for(reason in reasons){
            val kind=VideoFailurePolicy.classify(reason)
            assertEquals(VideoFailurePolicy.Kind.AUTH,kind)
            assertTrue(VideoFailurePolicy.actionable(kind))
            assertEquals(0,VideoFailurePolicy.maxRetries(kind))
        }
    }
    @Test fun ordinaryNetworkOutageRemainsRetryable(){
        assertFalse(VideoFailurePolicy.actionable(
            VideoFailurePolicy.classify("Temporary connection reset by peer")))
    }
}
