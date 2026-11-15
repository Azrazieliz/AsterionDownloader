package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoAgentAdmissionTest {
    @Test fun independentSecondaryCanClaimSelectedWebpageAboveHeadroom(){
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(
            VideoAgentAdmission.MIN_FREE_FOR_TWO_EXTRACTORS+1L,false))
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(5_000_000_000L,false))
    }

    @Test fun deviceMemoryPressureRetainsNativeLaneWithoutSecondPython(){
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            VideoAgentAdmission.MIN_FREE_FOR_TWO_EXTRACTORS-1L,false))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            3_000_000_000L,true))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            3_500_000_000L,false))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(0L,false))
    }

    @Test fun atMemoryThresholdSecondProcessIsEligible(){
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(
            VideoAgentAdmission.MIN_FREE_FOR_TWO_EXTRACTORS,false))
    }
}
