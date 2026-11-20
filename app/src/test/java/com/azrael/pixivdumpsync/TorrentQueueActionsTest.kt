package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentQueueActionsTest {
    @Test fun pausedAndFailedJobsOfferResumeNotPauseAndCanBeDiscarded(){
        for(status in listOf("Paused · user request","FAILED: tracker timeout",
            "STALLED: no peers")){
            val actions=TorrentTransfers.queueActionLabels(status)
            assertTrue(actions.contains("Resume transfer"))
            assertFalse(actions.contains("Pause transfer"))
            assertTrue(actions.contains("Discard from queue"))
        }
    }
    @Test fun activeJobsOfferPauseNotResumeAndCanBeDiscarded(){
        val actions=TorrentTransfers.queueActionLabels("Downloading 26% · peers 4")
        assertTrue(actions.contains("Pause transfer"))
        assertFalse(actions.contains("Resume transfer"))
        assertTrue(actions.contains("Discard from queue"))
    }
    @Test fun exportedOriginalsStayAccessibleWhenTheirQueueJobIsDiscarded(){
        val actions=TorrentTransfers.queueActionLabels(
            "EXPORTED · 2 selected originals saved to Downloads")
        assertTrue(actions.contains("Open exported originals"))
        assertTrue(actions.contains("Discard from queue"))
        assertFalse(actions.contains("Pause transfer"))
        assertFalse(actions.contains("Resume transfer"))
    }
    @Test fun quarantinedFilesNeedReviewButCanStillClearQueue(){
        val actions=TorrentTransfers.queueActionLabels("QUARANTINED · scan pending")
        assertTrue(actions.contains("Review & export selected files"))
        assertTrue(actions.contains("Discard from queue"))
        assertFalse(actions.contains("Pause transfer"))
    }
    @Test fun cancelledJobsCannotBeResumedOrDiscardedAgain(){
        assertFalse(TorrentTransfers.queueActionLabels(
            "Cancelled · selected torrent removed from queue")
            .contains("Discard from queue"))
    }
}
