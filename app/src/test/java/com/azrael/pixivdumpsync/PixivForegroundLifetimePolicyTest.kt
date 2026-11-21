package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PixivForegroundLifetimePolicyTest {
    @Test fun aDeniedSecondStartMustNotKillTheExistingBackfill(){
        assertTrue(PixivForegroundLifetimePolicy.keepExistingWorker(true))
    }

    @Test fun aDeniedFirstStartMustStopWithoutBeginningNewWork(){
        assertFalse(PixivForegroundLifetimePolicy.keepExistingWorker(false))
    }
}
