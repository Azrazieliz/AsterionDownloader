package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

/** Reuse native Kuroha's durable manual-backfill queue, not a second engine. */
class PixivUnifiedBackfillAdmissionTest {
    @Test fun backfillCanEnterExistingKurohaQueueDuringLive(){
        assertTrue(PixivUnifiedArtistAdmission.permit(true,SyncMode.BACKFILL))
    }
    @Test fun stillAvoidCompetingLiveAndDirectRequests(){
        assertFalse(PixivUnifiedArtistAdmission.permit(true,SyncMode.LIVE))
        assertFalse(PixivUnifiedArtistAdmission.permit(true,SyncMode.DIRECT))
    }
    @Test fun idleNativeEngineKeepsAllExistingRequests(){
        for(mode in listOf(SyncMode.LIVE,SyncMode.BACKFILL,SyncMode.DIRECT))
            assertTrue(PixivUnifiedArtistAdmission.permit(false,mode))
    }
}
