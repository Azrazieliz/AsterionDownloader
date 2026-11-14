package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsterionInAppUpdaterTest {
    @Test fun onlyStrictlyNewerVersionsPass() {
        assertTrue(AsterionInAppUpdater.newer(listOf(1, 3, 46), listOf(1, 3, 45)))
        assertFalse(AsterionInAppUpdater.newer(listOf(1, 3, 45), listOf(1, 3, 45)))
        assertFalse(AsterionInAppUpdater.newer(listOf(1, 2, 99), listOf(1, 3, 1)))
        assertEquals(listOf(1, 3, 46), AsterionInAppUpdater.parts("1.3.46-preview"))
        assertNull(AsterionInAppUpdater.parts("unknown"))
    }

    @Test fun acceptanceUsesOnlyItsOwnVersionedUpdateChannel(){
        assertEquals("acceptance",AsterionInAppUpdater.channelForPackage(
            "com.azrael.pixivdumpsync.acceptance"))
        assertEquals("preview",AsterionInAppUpdater.channelForPackage(
            "com.azrael.pixivdumpsync.nocturne"))
        assertEquals("release",AsterionInAppUpdater.channelForPackage(
            "com.azrael.pixivdumpsync"))
        assertTrue(AsterionInAppUpdater.matchesChannel("acceptance","acceptance"))
        assertFalse(AsterionInAppUpdater.matchesChannel("acceptance","preview"))
        assertFalse(AsterionInAppUpdater.matchesChannel("acceptance","release"))
        assertFalse(AsterionInAppUpdater.matchesChannel("preview","acceptance"))
        assertTrue(AsterionInAppUpdater.matchesChannel("release","production"))
        assertFalse(AsterionInAppUpdater.matchesChannel("release","acceptance"))
    }

    @Test fun updateDownloadOnlyAcceptsTrustedHttpsDomains() {
        assertTrue(AsterionInAppUpdater.trusted(
            "https://github.com/Azrazieliz/AsterionDownloader/releases/download/a/b.apk"))
        assertTrue(AsterionInAppUpdater.trusted(
            "https://release-assets.githubusercontent.com/github-production-release-asset/123"))
        assertFalse(AsterionInAppUpdater.trusted("http://github.com/release.apk"))
        assertFalse(AsterionInAppUpdater.trusted("https://github.com.attacker.example/release.apk"))
        assertFalse(AsterionInAppUpdater.trusted("https://user:pass@github.com/release.apk"))
        assertFalse(AsterionInAppUpdater.trusted("https://random.githubusercontent.com/payload.apk"))
    }

    @Test fun checksumMustBelongToExactApkFilename() {
        val wanted = "Asterion-Downloader-v1.3.46-Preview.apk"
        val expected = "a".repeat(64)
        val text = "b".repeat(64) + "  Other.apk\n" + expected + "  " + wanted + "\n"
        assertEquals(expected, AsterionInAppUpdater.checksumFromRaw(text, wanted))
    }

    @Test(expected = IllegalStateException::class)
    fun wrongChecksumFilenameFailsClosed() {
        AsterionInAppUpdater.checksumFromRaw(
            "b".repeat(64) + "  another-apk.apk", "Asterion-Downloader-v1.3.46-Preview.apk"
        )
    }

    @Test(expected = IllegalStateException::class)
    fun conflictingChecksumsFailClosed() {
        val name = "Asterion-Downloader-v1.3.46-Preview.apk"
        AsterionInAppUpdater.checksumFromRaw(
            "b".repeat(64) + "  " + name + "\n" + "a".repeat(64) + "  " + name,
            name
        )
    }
}
