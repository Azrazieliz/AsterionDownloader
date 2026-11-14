package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class CreatorArchiveSchedulerTest {
    @Test fun incompleteHistoryRequestsCatchup(){
        assertTrue(CreatorArchiveScheduler.shouldCatchup(false,0,""))
    }
    @Test fun completedHistoryWithDeferredNewAttachmentsRequestsCatchup(){
        assertTrue(CreatorArchiveScheduler.shouldCatchup(true,25,""))
    }
    @Test fun completedHistoryWithoutDeferredFilesDoesNotLoop(){
        assertFalse(CreatorArchiveScheduler.shouldCatchup(true,0,""))
    }
    @Test fun persistentSourceErrorsWaitForNormalRetryWindow(){
        assertFalse(CreatorArchiveScheduler.shouldCatchup(false,5,"Provider HTTP 403"))
    }
}
