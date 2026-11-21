package com.azrael.pixivdumpsync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PixivManualBackfillPriorityTest {
    @Test fun queuedManualBackfillPreemptsOnlyActiveLive(){
        assertTrue(PixivManualBackfillPriority.shouldPreempt(true,SyncMode.LIVE,false))
        for(mode in listOf(SyncMode.BACKFILL,SyncMode.DIRECT,SyncMode.CLOUD)){
            assertFalse(PixivManualBackfillPriority.shouldPreempt(true,mode,false))
        }
        assertFalse(PixivManualBackfillPriority.shouldPreempt(false,SyncMode.LIVE,false))
        assertFalse(PixivManualBackfillPriority.shouldPreempt(true,SyncMode.LIVE,true))
    }
}
