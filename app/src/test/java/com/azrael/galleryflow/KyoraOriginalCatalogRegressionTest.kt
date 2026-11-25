package com.azrael.galleryflow

import org.jsoup.nodes.Document
import org.junit.Assert.*
import org.junit.Test

/**
 * User-retired publishers must never wake up through a legacy generic
 * album root. Their previously saved original files and records remain
 * untouched; no read-only provider access is attempted by these tests.
 */
class KyoraOriginalCatalogRegressionTest {
    private fun rejectsRetiredRootWithoutFetching(site:String,root:String) {
        var requests=0
        val http=object:HttpClient(){
            override fun document(url:String,referer:String?):Document {
                requests++
                throw AssertionError("Retired $site fetched remote site: $url")
            }
        }
        val entity=SourceEntity(SourceId.GENERIC,
            "site:"+site,site,root,"site")
        val exception=assertThrows(AdapterException::class.java) {
            GenericSiteAdapter(http).enumerateGalleries(entity).toList()
        }
        assertTrue(exception.message.orEmpty().contains("Retired provider"))
        assertEquals("Retirement must not reconnect retired sources",0,requests)
    }

    @Test fun retiredFourKGirlRootNeverReseedsOldGalleryRows() {
        rejectsRetiredRootWithoutFetching("4kgirl.com","https://www.4kgirl.com/")
        assertTrue(GalleryRequestLedger.Request(SyncMode.BACKFILL,
            siteHost="4kgirl.com").retired)
        assertTrue(GalleryRequestLedger.Request(SyncMode.BACKFILL,
            siteSource="4kgirl").retired)
        assertFalse(GalleryRequestLedger.Request(SyncMode.BACKFILL,
            siteHost="4khd.com").retired)
    }

    @Test fun retiredPornPicsRootNeverReseedsOldGalleryRows() {
        rejectsRetiredRootWithoutFetching("pornpics.com",
            "https://www.pornpics.com/")
    }

    @Test fun retiredCelebStRootNeverReopensCreatorCollections() {
        rejectsRetiredRootWithoutFetching("celeb.st",
            "https://celeb.st/feed/hot")
    }
}
