package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformerFollowPolicyTest {
    @Test fun genuineTwoWordNameCanBeSelectedWithoutProviderProfile() {
        assertTrue(PerformerFollowPolicy.permittedName("Tina Kitsune"))
        assertTrue(PerformerFollowPolicy.permittedName("Tina   Kitsune"))
        assertTrue(PerformerFollowPolicy.permittedName("Yoruichi Shihouin"))
    }
    @Test fun shortAmbiguousOrGenericInputIsNotAConfirmedIdentity() {
        assertFalse(PerformerFollowPolicy.permittedName("JJ"))
        assertFalse(PerformerFollowPolicy.permittedName("x"))
        assertFalse(PerformerFollowPolicy.permittedName("model"))
        assertFalse(PerformerFollowPolicy.permittedName("girls"))
        assertFalse(PerformerFollowPolicy.permittedName("https://example.com/model"))
    }
}
