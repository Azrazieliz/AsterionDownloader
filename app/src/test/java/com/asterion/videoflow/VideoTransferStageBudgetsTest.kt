package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoTransferStageBudgetsTest {
    @Test fun newVerifiedCandidateHasFreshFirstByteWindow(){
        val priorResolution=75_000L
        val elapsedInNewTransfer=10_000L
        assertTrue(VideoTransferLimits.resolvingStalled(
            priorResolution+elapsedInNewTransfer,0))
        assertFalse(VideoTransferLimits.resolvingStalled(
            elapsedInNewTransfer,0))
        assertFalse(VideoTransferLimits.preparationBudgetExceeded(
            priorResolution+elapsedInNewTransfer,false))
    }
    @Test fun globalNoByteBudgetStillBoundsMultipleFailedCandidates(){
        assertFalse(VideoTransferLimits.preparationBudgetExceeded(
            VideoTransferLimits.TOTAL_FIRST_BYTE_BUDGET_MS-1,false))
        assertTrue(VideoTransferLimits.preparationBudgetExceeded(
            VideoTransferLimits.TOTAL_FIRST_BYTE_BUDGET_MS,false))
        assertFalse(VideoTransferLimits.preparationBudgetExceeded(
            VideoTransferLimits.TOTAL_FIRST_BYTE_BUDGET_MS,true))
    }
}
