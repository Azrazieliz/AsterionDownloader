package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectTransferStallPolicyTest {
    private val deadline=DirectTransferStallPolicy.ZERO_BYTE_TIMEOUT_MS
    @Test fun noBytesBeforeDeadlineStillConnecting(){
        assertFalse(DirectTransferStallPolicy.stalled(true,0L,100L,100L+deadline-1L))
    }
    @Test fun zeroBytePendingOrRunningAtDeadlineMustReleaseSlot(){
        assertTrue(DirectTransferStallPolicy.stalled(true,0L,100L,100L+deadline))
    }
    @Test fun receivedBytesNeverCancelledRegardlessOfAge(){
        assertFalse(DirectTransferStallPolicy.stalled(true,1L,100L,100L+deadline*30L))
        assertFalse(DirectTransferStallPolicy.stalled(true,3000L,100L,100L+deadline*30L))
    }
    @Test fun failedPausedAndDoneAreNotCancelled(){
        assertFalse(DirectTransferStallPolicy.stalled(false,0L,100L,100L+deadline*30L))
    }
    @Test fun missingAndFutureTimestampsAreNotConsideredStalls(){
        assertFalse(DirectTransferStallPolicy.stalled(true,0L,0L,deadline*30))
        assertFalse(DirectTransferStallPolicy.stalled(true,0L,100000L,50000L))
    }
}
