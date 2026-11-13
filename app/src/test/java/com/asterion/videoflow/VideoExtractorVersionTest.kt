package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoExtractorVersionTest {
    @Test fun pinnedWheelMatchesOfficialReleaseMetadata() {
        assertEquals("2026.10.26",VideoExtractorVersion.VERSION)
        assertEquals("yt_dlp-2026.10.26-py3-none-any.whl",
            VideoExtractorVersion.ASSET)
        assertEquals(64,VideoExtractorVersion.SHA256.length)
        assertTrue(VideoExtractorVersion.SHA256.matches(
            Regex("[0-9a-f]{64}")))
    }
}
