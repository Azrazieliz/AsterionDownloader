package com.azrael.pixivdumpsync

import org.junit.Assert.*
import org.junit.Test

class DoujinUnifiedRoutingTest {
    @Test fun bothNhenTaiHostsRouteIntoTheExistingZIPEngine(){
        assertEquals(DownloadKind.COMIC,SourceRouter.classify("https://nhentai.net/g/123456/"))
        assertEquals(DownloadKind.COMIC,SourceRouter.classify("https://nhentai.xxx/g/98765/"))
        assertEquals("123456",NhentaiChapters.galleryId("https://nhentai.net/g/123456/"))
        assertEquals("98765",NhentaiChapters.galleryId("https://nhentai.xxx/g/98765/"))
    }
    @Test fun doujinListingIsNotMistakenForACompleteGallery(){
        assertFalse(NhentaiChapters.isGallery("https://nhentai.net/language/english/"))
        assertFalse(NhentaiChapters.isGallery("https://nhentai.xxx/language/english/"))
        assertNull(NhentaiChapters.galleryId("https://nhentai.net/g/not-a-number"))
    }
}
