package com.azrael.pixivdumpsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A selected gallery must enter the gallery parser, not the file resolver. */
class ApprovedCosplayActionsTest {
    @Test fun firstPartyGalleryPagesAreAcceptedForSelectedAlbumWork(){
        assertTrue(ApprovedCosplayActions.isGalleryPage(
            "https://buondua.com/album/example-123"))
        assertTrue(ApprovedCosplayActions.isGalleryPage(
            "https://cosplaytele.com/gallery/example-12.html"))
    }

    @Test fun manualAlbumChecksReuseOneProviderRootNotScatteredSources(){
        val kiutaku=ApprovedCosplayActions.albumRoot("kiutaku.com","Kiutaku")!!
        val pornpics=ApprovedCosplayActions.albumRoot("pornpics.com","Porn Pics")!!
        assertEquals("site:kiutaku.com",kiutaku.stableId)
        assertEquals("site:pornpics.com",pornpics.stableId)
        assertEquals(com.azrael.galleryflow.SourceId.KIUTAKU,kiutaku.source)
        assertEquals(com.azrael.galleryflow.SourceId.GENERIC,pornpics.source)
        assertEquals("site",kiutaku.kind)
        assertEquals("site",pornpics.kind)
        assertNull(ApprovedCosplayActions.albumRoot("another-site.com","Other"))
    }

    @Test fun directMediaAndSiteHomeAreNotMisroutedAsGalleries(){
        assertFalse(ApprovedCosplayActions.isGalleryPage(
            "https://buondua.com/content/original-image.jpg"))
        assertFalse(ApprovedCosplayActions.isGalleryPage(
            "https://buondua.com/"))
        assertFalse(ApprovedCosplayActions.isGalleryPage("javascript:alert(1)"))
    }
}
