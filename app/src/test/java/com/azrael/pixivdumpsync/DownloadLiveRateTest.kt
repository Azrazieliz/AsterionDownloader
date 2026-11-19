package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class DownloadLiveRateTest {
    @Test fun frequentObserverEventsDoNotStarveRealByteRates() {
        var sample=DownloadLiveRate.measure(null,0L,1000L)
        val frames=listOf(1240L,1480L,1720L)
        for((index,t) in frames.withIndex()) {
            sample=DownloadLiveRate.measure(sample,
                (index+1)*1_024_000L,t)
            assertNull(sample.rateBytesPerSecond)
            assertEquals(1000L,sample.baselineTimeMs)
        }
        sample=DownloadLiveRate.measure(sample,4_096_000L,1960L)
        assertNotNull(sample.rateBytesPerSecond)
        assertTrue(sample.rateBytesPerSecond!!>4_000_000L)
        val held=DownloadLiveRate.measure(sample,5_120_000L,1200L+960L)
        assertEquals(sample.rateBytesPerSecond,held.rateBytesPerSecond)
        assertEquals(1960L,held.baselineTimeMs)
    }

    @Test fun stalledOrRolledBackTransfersDoNotClaimByteProgress() {
        val active=DownloadLiveRate.measure(
            DownloadLiveRate.Sample(1_000_000L,1000L),
            2_000_000L,2000L)
        assertEquals(1_000_000L,active.rateBytesPerSecond)
        assertNull(DownloadLiveRate.measure(active,2_000_000L,3000L)
            .rateBytesPerSecond)
        val reset=DownloadLiveRate.measure(active,100_000L,2100L)
        assertNull(reset.rateBytesPerSecond)
        assertEquals(100_000L,reset.bytes)
        assertNull(DownloadLiveRate.measure(active,2_200_000L,40_000L)
            .rateBytesPerSecond)
    }

    @Test fun boundedMeasurementResetsAfterLongSuspension() {
        val old=DownloadLiveRate.Sample(1000L,1000L)
        val fresh=DownloadLiveRate.measure(old,5_000_000L,60_000L)
        assertEquals(5_000_000L,fresh.bytes)
        assertNull(fresh.rateBytesPerSecond)
        assertEquals(60_000L,fresh.baselineTimeMs)
    }
}
