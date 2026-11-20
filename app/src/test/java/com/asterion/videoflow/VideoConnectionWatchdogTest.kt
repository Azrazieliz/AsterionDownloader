package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoConnectionWatchdogTest {
    private val limit=VideoTransferLimits.TOTAL_FIRST_BYTE_BUDGET_MS

    @Test fun oldResumableBytesCannotMakeConnectingRunForever() {
        val oldPart=128L*1024*1024
        val watchdog=VideoConnectionWatchdog(100L,oldPart)
        assertFalse(watchdog.exhausted(100L+limit-1L,oldPart))
        // A 128 MiB .part file from an earlier attempt is NOT new media.
        assertTrue(watchdog.exhausted(100L+limit,oldPart))
        assertTrue(watchdog.exhausted(100L+limit+40_000L,oldPart))
    }

    @Test fun genuinelyNewBytesExtendBudgetWithoutAFileSizeCap() {
        val monitor=VideoConnectionWatchdog(0L,5_000L)
        assertFalse(monitor.exhausted(limit-1L,5_000L))
        assertFalse(monitor.exhausted(limit-1L,5_001L))
        assertFalse(monitor.exhausted(limit*2L-2L,5_001L))
        assertTrue(monitor.exhausted(limit*2L-1L,5_001L))
    }

    @Test fun repeatedSourceCandidatesWithNoNewBytesDoNotResetClock() {
        val monitor=VideoConnectionWatchdog(5_000L,0L)
        for(poll in listOf(10_000L,50_000L,90_000L,190_000L))
            assertFalse(monitor.exhausted(poll,0L))
        assertTrue(monitor.exhausted(5_000L+limit,0L))
    }

    @Test fun nativeHlsVerifiedFragmentsCountWhenTopLevelStaysEmpty() {
        val monitor=VideoConnectionWatchdog(100L,0L)
        assertFalse(monitor.exhausted(limit,0L,2048L))
        assertFalse(monitor.exhausted(limit+50_000L,0L,4096L))
        assertTrue(monitor.exhausted(limit*2L+50_000L,0L,4096L))
    }

    @Test fun oldFragmentBytesWithoutAnIncreaseAreNotFreshEvidence() {
        val monitor=VideoConnectionWatchdog(0L,20_000L)
        assertFalse(monitor.exhausted(10_000L,20_000L))
        assertFalse(monitor.exhausted(20_000L,10_000L))
        assertTrue(monitor.exhausted(limit,20_000L))
    }
}
