package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoTransferJournalTest {
    @Test fun durableStageCarriesVerifiedMediaBytes(){
        val result=VideoTransferJournal.decode("receiving|1794598100000|1048576|1048576")
        assertEquals("receiving",result?.stage)
        assertEquals(1048576L,result?.verifiedBytes)
    }
    @Test fun nonReceivingStageStillCarriesLastVerifiedBytes(){
        val result=VideoTransferJournal.decode(
            "original_complete|1794598105000|finished|1048576")
        assertEquals(1048576L,result?.verifiedBytes)
    }
    @Test fun invalidOrNegativeByteCountsNeverBecomeProgress(){
        assertNull(VideoTransferJournal.decode("receiving|10|42|-1"))
        assertNull(VideoTransferJournal.decode("receiving|10|42|not-number"))
        assertNull(VideoTransferJournal.decode("receiving|10|bad"))
    }
}
