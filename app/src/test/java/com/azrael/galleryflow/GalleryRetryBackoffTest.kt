package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryRetryBackoffTest {
    @Test fun failedGalleryNeverRetriesEveryMinuteForever(){
        assertEquals(60_000L,GalleryRetryBackoff.delayForFailure(1))
        assertEquals(120_000L,GalleryRetryBackoff.delayForFailure(2))
        assertEquals(240_000L,GalleryRetryBackoff.delayForFailure(3))
        assertEquals(480_000L,GalleryRetryBackoff.delayForFailure(4))
        assertEquals(21_600_000L,GalleryRetryBackoff.delayForFailure(10))
        assertEquals(21_600_000L,GalleryRetryBackoff.delayForFailure(12))
        assertEquals(21_600_000L,GalleryRetryBackoff.delayForFailure(500))
    }
    @Test fun retryDelayCannotOverflowOrBeNegativeForOldInventory(){
        for(attempt in listOf(-100,0,1,2,4,12,1000000)){
            val delay=GalleryRetryBackoff.delayForFailure(attempt)
            assertTrue(delay>=60_000L)
            assertTrue(delay<=21_600_000L)
        }
    }
}
