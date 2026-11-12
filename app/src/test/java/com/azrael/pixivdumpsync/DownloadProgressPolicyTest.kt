package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressPolicyTest {
    @Test fun unknownTotalWithBytesCountsAsTransfer() {
        assertTrue(DownloadProgressPolicy.receiving(true,8192L,null))
        assertTrue(DownloadProgressPolicy.receiving(true,1L,0))
    }
    @Test fun knownPercentAlsoCounts() {
        assertTrue(DownloadProgressPolicy.receiving(true,0L,27))
    }
    @Test fun inactiveOrZeroByteDoesNotCount() {
        assertFalse(DownloadProgressPolicy.receiving(false,8192L,100))
        assertFalse(DownloadProgressPolicy.receiving(true,0L,null))
        assertFalse(DownloadProgressPolicy.receiving(true,0L,0))
    }
}
