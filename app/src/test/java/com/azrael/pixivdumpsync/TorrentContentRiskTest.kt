package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class TorrentContentRiskTest {
    @Test fun warnsAboutExecutablesDisguisedAsMedia() {
        assertEquals("Disguised executable",
            TorrentContentRisk.reviewReason("album/cover.jpg.exe"))
        assertNotNull(TorrentContentRisk.reviewReason("game/setup.exe"))
        assertNotNull(TorrentContentRisk.reviewReason("book/guide.docm"))
        assertNotNull(TorrentContentRisk.reviewReason("../payload.exe"))
        assertNull(TorrentContentRisk.reviewReason("scene/clip.mp4"))
    }
    @Test fun nonGameTransfersDoNotAutoSelectRiskyFiles(){
        assertFalse(TorrentContentRisk.selectByDefault("Videos","clip.mp4.exe"))
        assertTrue(TorrentContentRisk.selectByDefault("Videos","clip.mp4"))
        assertFalse(TorrentContentRisk.selectByDefault("Games","installer.exe"))
        assertTrue(TorrentContentRisk.selectByDefault("Games","assets.pak"))
        assertTrue(TorrentContentRisk.selectByDefault("Books","Novel.EPUB"))
        assertFalse(TorrentContentRisk.selectByDefault("Books","installer.exe"))
    }
}
