package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class PixivLegacySyncGateTest {
    @Test fun inaccessibleKurohaCannotDisableNewArtDirectOrCloud(){
        for(mode in listOf(SyncMode.LIVE,SyncMode.DIRECT,SyncMode.CLOUD)){
            assertNull(PixivLegacySyncGate.reason(mode,true,0,false,null,null))
            assertNull(PixivLegacySyncGate.reason(mode,true,1,true,
                "Permission expired","Storage exception"))
        }
    }
    @Test fun historicalBackfillRequiresRealReadableLegacyInventory(){
        assertNotNull(PixivLegacySyncGate.reason(SyncMode.BACKFILL,
            true,0,false,null,null))
        assertNotNull(PixivLegacySyncGate.reason(SyncMode.BACKFILL,
            true,1,true,null,null))
        assertNotNull(PixivLegacySyncGate.reason(SyncMode.BACKFILL,
            true,1,false,"Folder unavailable",null))
        assertNull(PixivLegacySyncGate.reason(SyncMode.BACKFILL,
            true,1,false,null,null))
        assertNull(PixivLegacySyncGate.reason(SyncMode.BACKFILL,
            false,0,false,null,null))
    }
}
