package com.azrael.pixivdumpsync

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

    @Test fun directMediaAndSiteHomeAreNotMisroutedAsGalleries(){
        assertFalse(ApprovedCosplayActions.isGalleryPage(
            "https://buondua.com/content/original-image.jpg"))
        assertFalse(ApprovedCosplayActions.isGalleryPage(
            "https://buondua.com/"))
        assertFalse(ApprovedCosplayActions.isGalleryPage("javascript:alert(1)"))
    }
}
