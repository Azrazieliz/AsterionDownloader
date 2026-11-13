package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TorrentQuarantineGateTest {
    @Test fun maliciousFilenameNeverClearsQuarantine(){
        val file=File.createTempFile("probe",".bin")
        try{
            file.writeText("not executable")
            assertTrue(TorrentQuarantineGate.preliminaryReview(
                file,"media/cover.jpg.exe").contains("REQUIRES SCAN"))
        }finally{file.delete()}
    }
    @Test fun safeLabelStillRequiresActualMalwareScan(){
        val file=File.createTempFile("probe",".mp4")
        try{
            file.writeText("not a complete real video")
            assertTrue(TorrentQuarantineGate.preliminaryReview(
                file,"clip.mp4").contains("malware scan required"))
        }finally{file.delete()}
    }
    @Test fun peerCountsDoNotConflateSeedersWithLeechers(){
        val value=TorrentPeerDiagnostics.describe(22,0,9,2,120000)
        assertTrue(value.contains("2 seeders"))
        assertTrue(value.contains("7 leechers"))
        assertTrue(value.contains("no piece progress"))
    }
    @Test fun trackerReannounceIsOncePerStallAndOnlyWhenNoConnectedSeeder(){
        assertTrue(TorrentPeerDiagnostics.shouldReannounce(120001,0,false))
        assertFalse(TorrentPeerDiagnostics.shouldReannounce(120001,0,true))
        assertFalse(TorrentPeerDiagnostics.shouldReannounce(40000,0,false))
        assertFalse(TorrentPeerDiagnostics.shouldReannounce(120001,2,false))
    }

}
