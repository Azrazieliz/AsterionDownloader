package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueRecoveryPolicyTest {
    @Test fun busyVideoDoesNotStarveOtherModules() {
        assertEquals(setOf("X","EPUB","Comic ZIP"),
            DownloadQueueRecoveryPolicy.eligibleSources(
                setOf("Video","X","EPUB","Comic ZIP"),setOf("Video")))
    }

    @Test fun sameModuleIsNeverRelaunchedWhileActive() {
        assertEquals(setOf("X"),DownloadQueueRecoveryPolicy.eligibleSources(
            setOf("Video","X"),setOf("Video")))
        assertEquals(emptySet<String>(),
            DownloadQueueRecoveryPolicy.eligibleSources(
                setOf("Video"),setOf("Video")))
    }
}
