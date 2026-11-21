package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Reuse native Kuroha's durable manual-backfill queue, not a second engine. */
class PixivUnifiedBackfillAdmissionTest {
    @Test fun backfillCanEnterExistingKurohaQueueDuringLive(){
        assertTrue(PixivUnifiedFeatures.permitArtistRequest(true,SyncMode.BACKFILL))
    }
    @Test fun stillAvoidCompetingLiveAndDirectRequests(){
        assertFalse(PixivUnifiedFeatures.permitArtistRequest(true,SyncMode.LIVE))
        assertFalse(PixivUnifiedFeatures.permitArtistRequest(true,SyncMode.DIRECT))
    }
    @Test fun idleNativeEngineKeepsAllExistingRequests(){
        for(mode in listOf(SyncMode.LIVE,SyncMode.BACKFILL,SyncMode.DIRECT))
            assertTrue(PixivUnifiedFeatures.permitArtistRequest(false,mode))
    }
}
