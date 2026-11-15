package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PixivLegacySyncGateTest {
    @Test fun workingLiveSyncAndDirectDownloadRemainIndependentOfKuroha(){
        for(mode in listOf(SyncMode.LIVE,SyncMode.DIRECT,SyncMode.CLOUD)){
            for((linked,scanning) in listOf(0 to false,1 to true)){
                val result=PixivLegacySyncGate.evaluate(mode,true,linked,scanning,
                    "Folder permission expired","Storage exception")
                assertTrue(result.proceed)
                assertNull(result.warning)
            }
        }
    }

    @Test fun historicalBackfillRunsEvenWhenLegacyFolderIsMissing(){
        val result=PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,
            true,0,false,null,null)
        assertTrue("Missing Kuroha SAF permissions must not disable Pixiv backfill",
            result.proceed)
        assertTrue(result.warning.orEmpty().contains("not linked"))
    }

    @Test fun historicalBackfillRunsDuringInventoryScanOrReadFailure(){
        val cases=listOf(
            PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,true,1,true,null,null),
            PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,true,1,false,
                "Folder unavailable",null),
            PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,true,1,false,
                null,"Storage exception")
        )
        cases.forEach{
            assertTrue("Optional deduplication cannot block real Pixiv downloads",
                it.proceed)
            assertNotNull(it.warning)
        }
    }

    @Test fun backfillWithReadableInventoryOrNoOldAppHasNoWarning(){
        val ready=PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,
            true,1,false,null,null)
        val standalone=PixivLegacySyncGate.evaluate(SyncMode.BACKFILL,
            false,0,false,null,null)
        assertTrue(ready.proceed)
        assertTrue(standalone.proceed)
        assertNull(ready.warning)
        assertNull(standalone.warning)
    }
}
