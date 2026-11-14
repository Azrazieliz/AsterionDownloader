package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class Verified4khdAlbumsTest {
    private val source="https://www.4khd.com/"
    @Test fun actualPublisherPostIsAnAlbum(){
        assertTrue(SiteBackfillProfiles.recognizesGalleryPage(source,
            "https://www.4khd.com/content/14/yeha-yeha-pure2.html"))
        assertTrue(SiteBackfillProfiles.recognizesGalleryPage(source,
            "https://www.4khd.com/content/14/babyyang-set-2.html"))
    }
    @Test fun homepageNavigationAndUnrelatedSitesAreNotAlbums(){
        for(path in listOf("/","/popular","/cosplay/","/album/",
            "/content/14/","/content/14/yeha-yeha-pure2.html/2")){
            assertFalse("Wrong album detection: "+path,
                SiteBackfillProfiles.recognizesGalleryPage(
                    source,"https://www.4khd.com"+path))
        }
        assertFalse(SiteBackfillProfiles.recognizesGalleryPage(source,
            "https://4khd.com.evil.example/content/14/yeha-yeha-pure2.html"))
    }
}
