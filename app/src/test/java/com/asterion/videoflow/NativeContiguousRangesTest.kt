package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeContiguousRangesTest {
    @Test fun acceptsContiguousServerLimited206Chunks() {
        assertEquals(
            NativeMediaDownloader.FullRange(0,255,1024),
            NativeMediaDownloader.contiguousRange("bytes 0-255/1024",0))
        assertEquals(
            NativeMediaDownloader.FullRange(256,511,1024),
            NativeMediaDownloader.contiguousRange("bytes 256-511/1024",256))
        assertEquals(
            NativeMediaDownloader.FullRange(512,1023,1024),
            NativeMediaDownloader.contiguousRange("bytes 512-1023/1024",512))
    }

    @Test fun refusesRepeatedOverlappingOrInvalidRanges() {
        assertNull(NativeMediaDownloader.contiguousRange("bytes 0-255/1024",256))
        assertNull(NativeMediaDownloader.contiguousRange("bytes 255-511/1024",256))
        assertNull(NativeMediaDownloader.contiguousRange("bytes 512-1024/1024",512))
        assertNull(NativeMediaDownloader.contiguousRange("bytes */1024",0))
        assertNull(NativeMediaDownloader.contiguousRange("bytes 0-255/*",0))
    }

    @Test fun existingFinalRangeGuardRemainsStrict() {
        assertNull(NativeMediaDownloader.completeRange("bytes 0-255/1024",0))
        assertEquals(
            NativeMediaDownloader.FullRange(256,1023,1024),
            NativeMediaDownloader.completeRange("bytes 256-1023/1024",256))
    }
}
