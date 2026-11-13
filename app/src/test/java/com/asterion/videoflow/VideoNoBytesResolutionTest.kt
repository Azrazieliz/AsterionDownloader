package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoNoBytesResolutionTest {
    @Test fun neverStopsARealTransferWithReceivedBytes(){
        assertFalse(VideoTransferLimits.resolvingStalled(60L*60_000L,1))
        assertFalse(VideoTransferLimits.resolvingStalled(
            VideoTransferLimits.NO_BYTES_RESOLVE_MS,2048))
    }
    @Test fun selectedProviderWithoutAnyOriginalBytesMustNotWaitForever(){
        assertFalse(VideoTransferLimits.resolvingStalled(
            VideoTransferLimits.NO_BYTES_RESOLVE_MS-1,0))
        assertTrue(VideoTransferLimits.resolvingStalled(
            VideoTransferLimits.NO_BYTES_RESOLVE_MS,0))
    }
}
