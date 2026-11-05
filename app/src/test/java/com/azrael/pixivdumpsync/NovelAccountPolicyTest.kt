package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAccountPolicyTest {
    @Test fun websiteAuthAndVerificationAreHeldForAccountAction() {
        for(error in listOf("Novel source HTTP 403: connect your account",
            "Novel source HTTP 401","interactive browser verification",
            "login required","access denied")) {
            assertTrue(NovelAccountPolicy.needsBrowserConnection(error))
        }
    }
    @Test fun temporaryNetworkAndMissingChaptersRemainRetryable() {
        for(error in listOf("Connection timed out","HTTP 503",
            "Missing 4 numbered chapters; refusing incomplete EPUB",
            "Novel index pagination did not finish")) {
            assertFalse(NovelAccountPolicy.needsBrowserConnection(error))
        }
    }
}
