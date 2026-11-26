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
    @Test fun cancelledSecurityReviewReceiptIsNotMisreportedAsQuarantine(){
        val receipt="Cancelled · selected torrent removed from queue; "+
            "unverified private pieces retained for security review"
        assertEquals("CANCELLED",
            TorrentTransfers.classifyStatus(receipt,0L,0L,100L))
        val actions=TorrentTransfers.queueActionLabels(receipt)
        assertFalse(actions.contains("Discard from queue"))
        assertFalse(actions.contains("Resume transfer"))
    }
    @Test fun activeAndQuarantinedTorrentStatusesRemainDistinct(){
        assertEquals("QUARANTINED",
            TorrentTransfers.classifyStatus(
                "QUARANTINED · retained for security review",0L,0L,100L))
        assertEquals("TRANSFERRING",
            TorrentTransfers.classifyStatus("Downloading 42%",9_000L,2048L,10_000L))
        assertEquals("WAITING_PEERS",
            TorrentTransfers.classifyStatus("Waiting",9_000L,0L,10_000L))
    }
    @Test fun stalledPartialPiecesAreNotCompletedQuarantine(){
        val receipt="fec0337b · STALLED: torrent stalled; partials quarantined · Resume keeps verified pieces"
        assertEquals("FAILED",TorrentTransfers.classifyStatus(receipt,9000L,0L,10000L))
        val actions=TorrentTransfers.queueActionLabels(receipt)
        assertTrue(actions.contains("Resume transfer"))
        assertFalse(actions.contains("Review & export selected files"))
    }
    @Test fun verificationAndExportRemainDistinctFromCompletedQuarantine(){
        assertEquals("VERIFYING",TorrentTransfers.classifyStatus(
            "fec0337b · Verifying selected files in private quarantine",0,0,100))
        assertEquals("VERIFYING",TorrentTransfers.classifyStatus(
            "fec0337b · Private quarantine · reviewed 2/3",0,0,100))
        assertEquals("EXPORTING",TorrentTransfers.classifyStatus(
            "fec0337b · QUARANTINED · Exporting verified file 2/3",0,0,100))
    }
    @Test fun queuedStateDoesNotInheritOldTransferMetrics(){
        val receipt="fec0337b · QUEUED · waiting for native metadata"
        assertEquals("QUEUED",TorrentTransfers.classifyStatus(receipt,9000L,2000L,10000L))
        assertTrue(TorrentTransfers.queueActionLabels(receipt).contains("Resume transfer"))
    }
    @Test fun cancelledJobsCannotBeResumedOrDiscardedAgain(){
        assertFalse(TorrentTransfers.queueActionLabels(
            "Cancelled · selected torrent removed from queue")
            .contains("Discard from queue"))
    }
}
