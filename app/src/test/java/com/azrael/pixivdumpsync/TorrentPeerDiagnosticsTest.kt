package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentPeerDiagnosticsTest {
    @Test fun connectedSeedersCannotMaskAStalledSelectedPiece(){
        assertTrue(TorrentPeerDiagnostics.shouldReannounce(120_000L,4,false))
        assertFalse(TorrentPeerDiagnostics.shouldReannounce(119_999L,4,false))
        assertFalse(TorrentPeerDiagnostics.shouldReannounce(999_000L,0,true))
        val description=TorrentPeerDiagnostics.describe(
            0,16,6,4,100_000L,0L,15L*1024*1024*1024,0L)
        assertTrue(description.contains("0 KiB/s payload"))
        assertTrue(description.contains("no verified piece progress"))
        assertTrue(description.contains("4 seeders"))
        assertTrue(description.contains("2 leechers"))
    }
    @Test fun verifiedBytesAreVisibleBeforeFullPercentRises(){
        val description=TorrentPeerDiagnostics.describe(
            0,50,4,2,5_000L,9L*1024*1024,4L*1024*1024*1024,50L)
        assertTrue(description.contains("verified 9/4096 MiB"))
        assertFalse(description.contains("no verified piece progress"))
    }
}
