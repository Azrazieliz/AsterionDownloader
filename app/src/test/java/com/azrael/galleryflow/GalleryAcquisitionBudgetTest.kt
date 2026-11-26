package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryAcquisitionBudgetTest {
    @Test fun remainingMetadataBudgetLimitsEachNetworkRequest(){
        var now=0L
        val budget=GalleryAcquisitionBudget({now},120_000L,{false})
        assertEquals(30_000,budget.nextTimeoutMs())
        now=119_500_000_000L
        assertEquals(500,budget.nextTimeoutMs())
        now=120_000_000_000L
        assertThrows(java.net.SocketTimeoutException::class.java){budget.nextTimeoutMs()}
    }
    @Test fun cancellationPrecedesAnyFurtherNetworkRequest(){
        val budget=GalleryAcquisitionBudget({0L},120_000L,{true})
        assertThrows(SyncCancelledException::class.java){budget.nextTimeoutMs()}
    }
}
