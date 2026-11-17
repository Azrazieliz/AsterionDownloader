package com.asterion.videoflow

import org.junit.Assert.*
import org.junit.Test

class VideoQueueClaimPolicyTest {
    @Test fun twoIndividuallySelectedVideosDoNotBlockOneAnother(){
        assertFalse(VideoQueueClaimPolicy.enforceEpisodeOrder(0L))
        assertFalse(VideoQueueClaimPolicy.enforceEpisodeOrder(-1L))
    }
    @Test fun scheduledSeriesRetainsEpisodeOrdering(){
        assertTrue(VideoQueueClaimPolicy.enforceEpisodeOrder(1L))
        assertTrue(VideoQueueClaimPolicy.enforceEpisodeOrder(92340L))
    }
}
