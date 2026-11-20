package com.azrael.galleryflow

import org.junit.Assert.*
import org.junit.Test

class SelectedAlbumCompletionPolicyTest {
    @Test fun selectedAlbumSuccessRetiresItsRequestWithoutSiteWideEntityCounter(){
        val request=GalleryRequestLedger.Request(
            SyncMode.BACKFILL,"site:all","buondua.net",
            "https://buondua.net/albums/selected",
            "Selected","buondua")
        val stats=SyncEngine.Stats(
            entities=0,galleriesSeen=1,galleriesCompleted=1,
            mediaDownloaded=49)
        assertTrue(GalleryCompletionPolicy.completed(request,stats,false))
    }
    @Test fun sameCompletionPolicyAppliesToEveryNativeGallerySource(){
        val sources=listOf("4khd","buondua","cosplaytele","kiutaku",
            "4kgirl","everia","generic")
        for(source in sources){
            val job=GalleryRequestLedger.Request(SyncMode.BACKFILL,
                "site:all",source+".test",
                "https://"+source+".test/a/selected",siteSource=source)
            assertTrue(source,GalleryCompletionPolicy.completed(job,
                SyncEngine.Stats(galleriesSeen=1,galleriesCompleted=1,
                    mediaDownloaded=12),false))
        }
    }
    @Test fun failedOrInterruptedOriginalDoesNotClearDurableSelection(){
        val request=GalleryRequestLedger.Request(
            SyncMode.BACKFILL,"site:all","4khd.com",
            "https://4khd.com/content/selected.html")
        assertFalse(GalleryCompletionPolicy.completed(request,
            SyncEngine.Stats(galleriesSeen=1,errors=1),false))
        assertFalse(GalleryCompletionPolicy.completed(request,
            SyncEngine.Stats(galleriesSeen=1,galleriesCompleted=1),true))
        assertFalse(GalleryCompletionPolicy.completed(request,
            SyncEngine.Stats(galleriesSeen=1,galleriesCompleted=0),false))
    }
    @Test fun siteWideBackfillStillRequiresRealGalleryCoverage(){
        val site=GalleryRequestLedger.Request(
            SyncMode.BACKFILL,"site:all","4khd.com")
        assertFalse(GalleryCompletionPolicy.completed(site,
            SyncEngine.Stats(entities=1,galleriesSeen=0),false))
        assertTrue(GalleryCompletionPolicy.completed(site,
            SyncEngine.Stats(entities=1,galleriesSeen=1),false))
    }
}
