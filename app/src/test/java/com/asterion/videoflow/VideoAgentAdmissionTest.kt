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

    @Test fun twelveGbPhoneWithThreeGbFreeAndNoPressureAllowsParallelExtractor(){
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(
            3_413_057_536L,false,12_000_000_000L,1_000_000_000L))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            2_000_000_000L,false,12_000_000_000L,1_000_000_000L))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            3_400_000_000L,true,12_000_000_000L,1_000_000_000L))
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            2_700_000_000L,false,12_000_000_000L,1_900_000_000L))
        // The owner's November 21 diagnostic: 3.104 GB available,
        // lowMemory=false; a genuine second selected-page extractor is eligible.
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(
            3_104_866_304L,false,12_000_000_000L,1_900_000_000L))
        // OS-reported pressure always wins, even when plenty of bytes remain.
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            3_104_866_304L,true,12_000_000_000L,1_900_000_000L))
        // Older 8GB phones without headroom stay on native-only lane.
        assertTrue(VideoAgentAdmission.secondaryNativeOnly(
            3_400_000_000L,false,8_000_000_000L,900_000_000L))
    }

    @Test fun twoRecentPrimaryLowMemoryExitsKeepSecondaryNativeOnly(){
        val now=1_795_360_000_000L
        val minute=60_000L
        assertTrue(VideoAgentAdmission.repeatedRecentPrimaryMemoryKills(
            listOf(now-7L*60*minute,now-6L*60*minute),now))
        assertFalse(VideoAgentAdmission.repeatedRecentPrimaryMemoryKills(
            listOf(now-25L*60*minute,now-26L*60*minute),now))
        assertFalse(VideoAgentAdmission.repeatedRecentPrimaryMemoryKills(
            listOf(now-2L*60*minute),now))
        assertFalse(VideoAgentAdmission.repeatedRecentPrimaryMemoryKills(
            listOf(now+minute,now-2L*60*minute),now))
    }
    @Test fun atMemoryThresholdSecondProcessIsEligible(){
        assertFalse(VideoAgentAdmission.secondaryNativeOnly(
            VideoAgentAdmission.MIN_FREE_FOR_TWO_EXTRACTORS,false))
    }
}
