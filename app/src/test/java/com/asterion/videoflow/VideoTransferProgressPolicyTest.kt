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
}
