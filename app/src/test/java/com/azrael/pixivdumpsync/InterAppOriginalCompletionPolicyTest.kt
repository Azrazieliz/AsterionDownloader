package com.azrael.pixivdumpsync

import android.app.DownloadManager
import org.junit.Assert.*
import org.junit.Test

class InterAppOriginalCompletionPolicyTest {
    @Test fun httpSuccessWithoutVerifiedFileMustNeverComplete(){
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"UNVERIFIED",false))
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,null,null))
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"INVALID",true))
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"DISMISSED",true))
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"STALLED",true))
    }

    @Test fun actualSavedOriginalCanCompleteAndQueuedCannot(){
        assertEquals("COMPLETED",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"UNVERIFIED",true))
        assertEquals("COMPLETED",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_SUCCESSFUL,"VERIFIED",true))
        assertEquals("ERROR",InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_FAILED,null,null))
        assertNull(InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_RUNNING,"UNVERIFIED",true))
        assertNull(InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_PENDING,null,true))
        assertNull(InterAppOriginalCompletionPolicy.resolve(
            DownloadManager.STATUS_PAUSED,null,false))
    }
}
