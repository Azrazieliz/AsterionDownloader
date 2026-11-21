package com.asterion.videoflow

import org.junit.Assert.assertEquals
import org.junit.Test

/** Freeze the restored universal source-resolution allowance: JAVIDEO must
 * not silently receive a 35-second exception again.
 */
class JavideoPublisherResolveBudgetTest {
    @Test fun originalSelectedPlayerHasSameDeadlineAsOtherProviders() {
        assertEquals(80_000L, VideoTransferLimits.NO_BYTES_RESOLVE_MS)
    }
}
