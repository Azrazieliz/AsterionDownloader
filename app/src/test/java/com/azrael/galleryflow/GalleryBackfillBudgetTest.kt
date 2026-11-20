package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class GalleryBackfillBudgetTest {
    @Test fun everyProviderIsBoundedBeforeTheNextOneStarts(){
        assertEquals(1,GalleryBackfillBudget.PRIOR_RETRIES)
        assertEquals(4,GalleryBackfillBudget.NEW_GALLERIES_PER_PASS)
        for(provider in listOf("4khd","buondua","cosplaytele",
            "kiutaku","generic","4kgirl","everia")){
            assertFalse(provider,GalleryBackfillBudget.hasMore(0))
            assertFalse(provider,GalleryBackfillBudget.hasMore(3))
            assertTrue(provider,GalleryBackfillBudget.hasMore(4))
            assertFalse(provider,GalleryBackfillBudget.hasMore(0,true))
            assertTrue(provider,GalleryBackfillBudget.hasMore(1,true))
            assertTrue(provider,GalleryBackfillBudget.hasMore(30_845))
        }
    }
}
