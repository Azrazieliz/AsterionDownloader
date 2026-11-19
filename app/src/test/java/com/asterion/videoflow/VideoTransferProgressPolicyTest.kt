package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoTransferProgressPolicyTest {
    @Test fun percentAloneIsNotCalledDownloaded(){
        val text=VideoTransferProgressPolicy.description(7,0)
        assertTrue(text.contains("Extractor reports 7%"))
        assertFalse(text.contains("bytes verified"))
    }
    @Test fun realByteCountsAreDistinguishedFromExtractorEstimates(){
        val text=VideoTransferProgressPolicy.description(6,65536)
        assertTrue(text.contains("65536 original bytes verified"))
    }

    @Test fun selectedJkNativeUsesOnlyFreshVerifiedFragmentJournalBytes(){
        fun observed(jk:Boolean,top:Long,stage:String?,updated:Long?,
                     recorded:Long?,start:Long=1000L):Long =
            VideoTransferProgressPolicy.observedBytes(
                jk,top,stage,updated,recorded,start)

        assertEquals(1_415_615_560L,
            observed(true,0L,"fragments",1100L,1_415_615_560L))
        assertEquals(100_000L,
            observed(true,100_000L,"fragments",1100L,50_000L))
        assertEquals(0L,observed(true,0L,"fragments",900L,9_000L))
        assertEquals(0L,observed(true,0L,"transfer",1100L,9_000L))
        assertEquals(0L,observed(true,0L,"fragments",null,9_000L))
        assertEquals(0L,observed(true,0L,"fragments",1100L,null))
        assertEquals(0L,observed(false,0L,"fragments",1100L,9_000L))
        assertEquals(1200L,observed(false,1200L,"fragments",1100L,9_000L))
    }
}
