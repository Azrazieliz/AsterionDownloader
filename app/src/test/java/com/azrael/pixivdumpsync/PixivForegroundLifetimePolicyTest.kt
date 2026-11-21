package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PixivForegroundLifetimePolicyTest {
    @Test fun aDeniedSecondStartMustNotKillTheExistingBackfill(){
        assertTrue(PixivForegroundLifetimePolicy.keepExistingWorker(true))
    }

    @Test fun oldCompletedWorkerCannotStopNewerBackfill(){
        assertFalse(PixivForegroundLifetimePolicy.ownsCompletion(8L,9L))
        assertTrue(PixivForegroundLifetimePolicy.ownsCompletion(9L,9L))
    }

    @Test fun aDeniedFirstStartMustStopWithoutBeginningNewWork(){
        assertFalse(PixivForegroundLifetimePolicy.keepExistingWorker(false))
    }
}
