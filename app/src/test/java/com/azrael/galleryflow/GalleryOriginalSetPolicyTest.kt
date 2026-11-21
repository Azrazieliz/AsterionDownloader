package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryOriginalSetPolicyTest {
    @Test fun unrelatedHistoricRetryRowsMustNotStarveCorrectSelectedOriginals(){
        val requested=setOf("selected-photo-01","selected-photo-02")
        val valid=setOf("selected-photo-01","selected-photo-02")
        assertTrue(GalleryOriginalSetPolicy.complete(requested,valid))
    }
    @Test fun missingOrZeroByteRequestedOriginalNeverPasses(){
        assertFalse(GalleryOriginalSetPolicy.complete(
            setOf("selected-photo-01","selected-photo-02"),
            setOf("selected-photo-01")))
        assertFalse(GalleryOriginalSetPolicy.complete(emptySet(),setOf("stale-photo")))
        assertFalse(GalleryOriginalSetPolicy.complete(
            setOf("selected-photo-02"),setOf("stale-photo")))
    }
    @Test fun deduplicatedVerifiedFileCanStillCompleteItsOwnOriginal(){
        assertTrue(GalleryOriginalSetPolicy.complete(
            setOf("a","b"),setOf("a","b","historic-unrelated")))
    }
}
