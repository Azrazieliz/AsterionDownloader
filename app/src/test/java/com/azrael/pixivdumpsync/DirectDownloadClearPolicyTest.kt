package com.azrael.pixivdumpsync

import android.app.DownloadManager
import org.junit.Assert.*
import org.junit.Test

class DirectDownloadClearPolicyTest {
    @Test fun noSavedOrRunningOriginalCanBeAcknowledgedAsFailure(){
        assertTrue(DirectDownloadClearPolicy.mayAcknowledge(
            DownloadManager.STATUS_FAILED,"STALLED"))
        assertTrue(DirectDownloadClearPolicy.mayAcknowledge(
            DownloadManager.STATUS_SUCCESSFUL,"INVALID"))
        for(status in listOf(DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,DownloadManager.STATUS_PAUSED)){
            assertFalse(DirectDownloadClearPolicy.mayAcknowledge(status,"INVALID"))
        }
        assertFalse(DirectDownloadClearPolicy.mayAcknowledge(
            DownloadManager.STATUS_SUCCESSFUL,"VERIFIED"))
        assertFalse(DirectDownloadClearPolicy.mayAcknowledge(
            DownloadManager.STATUS_SUCCESSFUL,"UNVERIFIED"))
    }
}
