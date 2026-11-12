package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectRetryPolicyTest {
    @Test fun scheduledRetryBacksOffAndStopsAfterCap() {
        assertFalse(DirectRetryPolicy.shouldAttempt(true,1002,1,10_000,9_999,false))
        assertTrue(DirectRetryPolicy.shouldAttempt(true,1002,1,10_000,10_000,false))
        assertFalse(DirectRetryPolicy.shouldAttempt(true,1002,5,0,20_000,false))
        assertFalse(DirectRetryPolicy.shouldAttempt(false,1002,0,0,20_000,false))
    }

    @Test fun authorizationErrorsRequireManualRetry() {
        for(code in listOf(401,402,403,404,410)) {
            assertFalse(DirectRetryPolicy.shouldAttempt(true,code,0,0,1_000,false))
            assertTrue(DirectRetryPolicy.shouldAttempt(true,code,5,10_000,1_000,true))
        }
        assertFalse(DirectRetryPolicy.shouldAttempt(false,403,0,0,1_000,true))
    }

    @Test fun manualRetryDoesNotLieForActiveOrQueuedRequests() {
        assertFalse(DirectRetryPolicy.manualRetryable(false,false,false))
        assertTrue(DirectRetryPolicy.manualRetryable(true,false,false))
        assertTrue(DirectRetryPolicy.manualRetryable(false,true,false))
        assertTrue(DirectRetryPolicy.manualRetryable(false,false,true))
    }

    @Test fun backoffIsBoundedAndExponential() {
        assertEquals(30_000L,DirectRetryPolicy.backoffMs(0))
        assertEquals(60_000L,DirectRetryPolicy.backoffMs(1))
        assertEquals(480_000L,DirectRetryPolicy.backoffMs(4))
        assertEquals(1_920_000L,DirectRetryPolicy.backoffMs(9))
    }
}
